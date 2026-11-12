(ns presa.proxy
  "A server that fronts several MCP servers as one. Each backend is a
  presa.client connection; its tools and prompts appear under a prefix
  (\"<prefix>_<name>\"), its resources and resource templates under their own
  URIs. Calls are forwarded with the caller's progress token and log level;
  a backend's list changes resync the proxy and reach the proxy's listeners.

  Input: a 2026-07-28 backend's input_required is passed to the caller and
  its answers back, with the backend's requestState kept in the proxy's own
  signed state. An initialize-era backend asks with requests of its own
  (elicitation/create, sampling/createMessage, roots/list) while the call
  runs: the proxy parks the call, asks the caller with input_required, and
  hands the caller's answer to the backend when the caller retries. A parked
  call lives in this process; one the caller does not answer within the
  backend client's timeout fails the backend's request. A backend asks only
  for what its client declared when it connected (see presa.client/connect
  :capabilities).

  Logs: a backend's log messages about a call reach the caller through
  presa.server/log!, filtered by the caller's level.

  Cancellation: when the caller cancels a call, the proxy cancels it at the
  backend (notifications/cancelled), and fails any request the backend is
  waiting on; a parked call is freed when its initialize-era caller gives
  up on presa's request.

  Over stdio, a backend's log messages and requests cannot be matched to a
  call, so neither is forwarded: they go to the backend client's own :on-log
  and :handlers. Backends on in-process and HTTP transports are matched.
  Subscriptions to backend resources are not forwarded."
  (:require [presa.client :as client]
            [presa.server :as server])
  (:import (java.security SecureRandom)
           (java.util Base64)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defn- forward-progress [ctx]
  (fn [{:strs [progress total message]}]
    (server/progress! ctx progress (cond-> {} total (assoc :total total) message (assoc :message message)))))

(defn- forward-log [ctx]
  (fn [{:strs [level data logger]}]
    (when (string? level)
      (try (server/log! ctx level data logger) (catch Exception _ nil)))))

(defn- call-opts [ctx]
  (cond-> {:input :return
           :on-progress (forward-progress ctx)
           :on-log (forward-log ctx)
           ;; the backend asks for what the caller can answer
           :meta {"io.modelcontextprotocol/clientCapabilities" (or (:client-capabilities ctx) {})}}
    (:log-level ctx) (assoc :log-level (:log-level ctx))))

(def ^:private input-capabilities
  {"elicitation/create" "elicitation" "sampling/createMessage" "sampling" "roots/list" "roots"})

(defn- new-id []
  (let [b (byte-array 18)]
    (.nextBytes (SecureRandom.) b)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)))

(declare watch-parked!)

(defn- park!
  "Keep a call waiting on the caller's answer to ask, an initialize-era
  backend's request; returns the proxy's input_required. When the caller
  cannot answer it (it did not declare the capability), the backend's
  request fails now and the caller gets the -32021 that presa sends for
  the input_required."
  [parked call ctx {:keys [method params reply]}]
  (let [id (new-id)
        asked {"backend" {"method" method "params" params}}]
    (if (contains? (:client-capabilities ctx) (input-capabilities method))
      (do (swap! parked assoc id (assoc call :reply reply :asked asked))
          (watch-parked! parked id call ctx))
      (deliver reply ::refused))
    (server/input-required asked {"parked" id})))

(defn- settle
  "What a call that ended returns to the caller. An input_required from a
  2026-07-28 backend becomes the proxy's own, carrying the backend's
  requestState in the proxy's state; the retry brings the answers back."
  [result]
  (if (= "input_required" (get result "resultType"))
    (server/input-required (get result "inputRequests" {})
                           (cond-> {"proxied" true}
                             (contains? result "requestState") (assoc "backendState" (get result "requestState"))))
    result))

(defn- cancel-call!
  "Stop call: fail the request the backend is waiting on, if any, and
  interrupt the call, which sends the backend notifications/cancelled."
  [{:keys [work pending]}]
  (some-> pending deref (deliver ::cancelled))
  (some-> work future-cancel)
  nil)

(defn- cancelled? [ctx] (boolean (some-> (:cancelled? ctx) (apply []))))

(def ^:private slice-ms 100)

(defn- next-event
  "Wait for what the call does next: [:ask ask], [:done result] or [:error e].
  When the caller's request is cancelled meanwhile (its cancelled? flag, or
  an interrupt of this thread), the call is cancelled too."
  [{:keys [^LinkedBlockingQueue events timeout-ms] :as call} ctx]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms 1000)]
    (loop []
      (let [e (try (.poll events (long slice-ms) TimeUnit/MILLISECONDS)
                   (catch InterruptedException ie
                     (cancel-call! call)
                     (throw ie)))]
        (cond
          e e
          (cancelled? ctx) (do (cancel-call! call)
                               [:error (ex-info "The caller cancelled the request" {:kind :cancelled})])
          (> (System/currentTimeMillis) deadline)
          (do (cancel-call! call)
              [:error (ex-info (str "The backend did not answer within " timeout-ms " ms") {:kind :timeout})])
          :else (recur))))))

(defn- watch-parked!
  "While the call parked under id waits, cancel it if the request that
  parked it is cancelled: an initialize-era caller that gives up on the
  request presa sent it."
  [parked id call ctx]
  (future
    (loop []
      (when (contains? @parked id)
        (if (cancelled? ctx)
          (when (get (first (swap-vals! parked dissoc id)) id)
            (cancel-call! call))
          (do (Thread/sleep (long slice-ms)) (recur)))))))

(defn- follow
  "Follow call until it ends or asks for input."
  [parked call ctx]
  (let [[kind v] (next-event call ctx)]
    (case kind
      :ask (park! parked call ctx v)
      :done (settle v)
      :error (throw v))))

(defn- start-call
  "Send method with params to c in the background. Requests the backend
  sends for the call become :ask events whose :reply the caller's answer
  is delivered to."
  [c method params ctx parked]
  (let [events (LinkedBlockingQueue.)
        timeout-ms (or (get-in c [:opts :timeout-ms]) client/default-timeout-ms)
        ;; the reply the backend's current request waits on
        pending (atom nil)
        ask (fn [method]
              (fn [params]
                (let [reply (promise)
                      ask {:method method :params params :reply reply}]
                  (reset! pending reply)
                  (.put events [:ask ask])
                  (let [answer (deref reply timeout-ms ::timeout)]
                    (case answer
                      ::timeout
                      ;; the caller never came back: forget the parked call
                      (do (swap! parked (fn [m] (into {} (remove (fn [[_ call]] (= events (:events call)))) m)))
                          (throw (ex-info "The caller did not answer in time"
                                          {:presa/error {:code -32603 :message "The caller did not answer in time"}})))
                      ::refused
                      (throw (ex-info (str "The caller cannot answer " method)
                                      {:presa/error {:code -32601 :message (str "The caller cannot answer " method)}}))
                      ::cancelled
                      (throw (ex-info "The caller cancelled the request"
                                      {:presa/error {:code -32603 :message "The caller cancelled the request"}}))
                      answer)))))
        handlers (into {} (map (fn [m] [m (ask m)])) (keys input-capabilities))
        work (future
               (try (.put events [:done (client/request c method params (assoc (call-opts ctx) :handlers handlers))])
                    (catch Throwable e (.put events [:error e]))))]
    {:events events :timeout-ms timeout-ms :work work :pending pending}))

(defn- forward
  "Send method with params to c on behalf of the request of ctx, or carry
  on with a call parked on the caller's answer (see the namespace doc)."
  [parked c method params ctx]
  (let [state (:state ctx)]
    (if-let [parked-id (get state "parked")]
      (let [gone #(ex-info "The proxied call is no longer waiting for input"
                           {:presa/error {:code -32602 :message "The proxied call is no longer waiting for input"}})
            answer (server/input-response ctx "backend")]
        (if (nil? answer)
          ;; asked again for the same input
          (server/input-required (:asked (or (get @parked parked-id) (throw (gone)))) state)
          ;; one retry claims the call
          (let [call (get (first (swap-vals! parked dissoc parked-id)) parked-id)]
            (when-not call (throw (gone)))
            (deliver (:reply call) answer)
            (follow parked (dissoc call :reply :asked) ctx))))
      (let [params (cond-> params
                     (seq (:input-responses ctx)) (assoc "inputResponses" (:input-responses ctx))
                     (get state "backendState") (assoc "requestState" (get state "backendState")))]
        (follow parked (start-call c method params ctx parked) ctx)))))

(defn- strip [result]
  (dissoc result "resultType" "_meta" "ttlMs" "cacheScope"))

(defn- tool-defs [prefix sep c parked]
  (for [{:strs [name title description inputSchema outputSchema annotations]} (client/list-tools c)]
    (cond-> {:name (str prefix sep name)
             :input-schema inputSchema
             :handler (fn [arguments ctx]
                        (let [r (forward parked c "tools/call" {"name" name "arguments" arguments} ctx)]
                          (if (contains? r ::server/input-required) r (strip r))))}
      title (assoc :title title)
      description (assoc :description description)
      outputSchema (assoc :output-schema outputSchema)
      annotations (assoc :annotations annotations))))

(defn- complete-fns [c ref arg-names]
  (into {} (for [a arg-names]
             [a (fn [value others _]
                  (client/complete c ref a value others))])))

(defn- prompt-defs [prefix sep c parked]
  (for [{:strs [name title description arguments]} (client/list-prompts c)]
    (cond-> {:name (str prefix sep name)
             :arguments (mapv (fn [{:strs [name description required]}]
                                (cond-> {:name name}
                                  description (assoc :description description)
                                  (some? required) (assoc :required required)))
                              arguments)
             :handler (fn [args ctx] (let [r (forward parked c "prompts/get" {"name" name "arguments" args} ctx)]
                                       (if (contains? r "messages") (strip r) r)))
             :complete (complete-fns c {"type" "ref/prompt" "name" name} (map #(get % "name") arguments))}
      title (assoc :title title)
      description (assoc :description description))))

(defn- read-handler [c uri-of parked]
  (fn [& args]
    (let [ctx (last args)
          r (forward parked c "resources/read" {"uri" (uri-of ctx)} ctx)]
      (if (contains? r "contents") (get r "contents") r))))

(defn- resource-defs [c parked]
  (for [{:strs [uri name title description mimeType size]} (client/list-resources c)]
    (cond-> {:uri uri :name name :handler (read-handler c (constantly uri) parked)}
      title (assoc :title title) description (assoc :description description)
      mimeType (assoc :mime-type mimeType) size (assoc :size size))))

(defn- template-defs [c parked]
  (for [{:strs [uriTemplate name title description mimeType]} (client/list-resource-templates c)]
    (cond-> {:uri-template uriTemplate :name name
             :handler (read-handler c :uri parked)
             :complete (complete-fns c {"type" "ref/resource" "uri" uriTemplate}
                                     (map second (re-seq #"\{\+?([A-Za-z0-9_.]+)\}" uriTemplate)))}
      title (assoc :title title) description (assoc :description description)
      mimeType (assoc :mime-type mimeType))))

(defn- has? [c capability] (contains? (:server-capabilities (client/info c)) capability))

(defn- catalog
  "The definitions a backend contributes."
  [{:keys [prefix client separator] ::keys [parked] :or {separator "_"}}]
  {:tools (if (has? client "tools") (vec (tool-defs prefix separator client parked)) [])
   :prompts (if (has? client "prompts") (vec (prompt-defs prefix separator client parked)) [])
   :resources (if (has? client "resources") (vec (resource-defs client parked)) [])
   :templates (if (has? client "resources") (vec (template-defs client parked)) [])})

(defn part
  "One backend, {:prefix p :client c} (`:separator` overrides \"_\"), as a
  presa.part part: its tools and prompts under the prefix, its resources and
  resource templates, each call forwarded as proxy-server forwards it (input,
  progress, logs, cancellation; see the namespace doc). The definitions are
  listed once: a part does not follow the backend's list changes, which
  proxy-server does. The caller closes the client."
  [backend]
  (let [{:keys [tools prompts resources templates]} (catalog (assoc backend ::parked (atom {})))]
    {:tools tools :prompts prompts :resources resources :resource-templates templates}))

(defn- check-unique! [kind ks]
  (when-let [dups (seq (for [[k n] (frequencies ks) :when (> n 1)] k))]
    (throw (ex-info (str "Backends share " kind ": " (vec dups)) {:kind :invalid-input kind (vec dups)}))))

(def ^:private kinds
  "Each kind of definition: its catalog key and the key that names it."
  [[:tools :name "tool"] [:prompts :name "prompt"] [:resources :uri "resource"]
   [:templates :uri-template "resource template"]])

(defn- without-taken
  "now without the definitions whose names others (catalogs of the other
  backends) already have; each one skipped is logged."
  [now others log]
  (reduce (fn [now [k key-fn what]]
            (let [taken (set (map key-fn (mapcat k others)))
                  {skipped true kept false} (group-by #(contains? taken (key-fn %)) (get now k))]
              (doseq [d skipped]
                (log "proxy: skipped " what " " (key-fn d) ", which another backend already has"))
              (assoc now k (vec kept))))
          now kinds))

(defn- sync!
  "Bring srv's definitions from a backend in line with now, what it lists
  now; previous is what it listed before."
  [srv previous now]
  (let [swap-defs (fn [k key-fn add! remove!]
                    (let [old (set (map key-fn (get previous k)))
                          new (set (map key-fn (get now k)))]
                      (doseq [gone (remove new old)] (remove! srv gone))
                      (doseq [d (get now k)] (add! srv d))))]
    (swap-defs :tools :name server/add-tool! server/remove-tool!)
    (swap-defs :prompts :name server/add-prompt! server/remove-prompt!)
    (swap-defs :resources :uri server/add-resource! server/remove-resource!)
    (swap-defs :templates :uri-template server/add-resource-template! server/remove-resource-template!)
    now))

(defn proxy-server
  "A proxy over backends: {:prefix p :client c} maps, where c is a connected
  presa.client (`:separator` overrides \"_\" between prefix and name). opts
  are presa.server/server options (:name and :version required); the proxy
  sets :list-changed itself, and :logging unless opts set it. Returns {:server srv :close! f}: serve srv on
  any transport, and (close!) stops following the backends' changes and
  closes their clients.

  Prefixed tool and prompt names, resource URIs and template URIs must not
  collide across backends. When a backend's list changes, its definitions
  are fetched again, one fetch at a time per backend; one whose name
  another backend already has is skipped and logged. :log in opts is
  (fn [& parts]) for that and for failed fetches (default: a line on
  stderr)."
  [backends opts]
  (let [log (or (:log opts) (fn [& parts] (binding [*out* *err*] (println (apply str parts)))))
        opts (dissoc opts :log)
        parked (atom {})
        backends (vec (map-indexed (fn [i b] (assoc b ::index i ::parked parked)) backends))
        catalogs (atom (into {} (map (fn [b] [(::index b) (catalog b)])) backends))
        all (fn [k] (mapcat k (vals @catalogs)))]
    (check-unique! :tools (map :name (all :tools)))
    (check-unique! :prompts (map :name (all :prompts)))
    (check-unique! :resources (map :uri (all :resources)))
    (check-unique! :resource-templates (map :uri-template (all :templates)))
    (let [srv (server/server (merge {:logging true} opts {:list-changed true
                                                          :tools (vec (all :tools))
                                                          :prompts (vec (all :prompts))
                                                          :resources (vec (all :resources))
                                                          :resource-templates (vec (all :templates))}))
          resync! (fn [b]
                    (try (let [i (::index b)
                               now (without-taken (catalog b) (vals (dissoc @catalogs i)) log)]
                           ;; sync! changes srv, so it runs once, outside swap!
                           (sync! srv (get @catalogs i) now)
                           (swap! catalogs assoc i now))
                         (catch Exception e
                           (log "proxy: could not refetch a backend's definitions: " (or (ex-message e) (class e))))))
          ;; per backend :idle, :running, or :dirty (running, and changed
          ;; again since it began): resyncs of a backend never overlap, and
          ;; a burst of changes collapses into one more
          states (vec (repeatedly (count backends) #(atom :idle)))
          request-resync! (fn [b]
                            (let [st (states (::index b))
                                  [before _] (swap-vals! st {:idle :running :running :dirty :dirty :dirty})]
                              (when (= :idle before)
                                (future
                                  (loop []
                                    (resync! b)
                                    (let [[before _] (swap-vals! st {:dirty :running :running :idle})]
                                      (when (= :dirty before) (recur))))))))
          followed (vec (for [{:keys [client] :as b} backends
                              :let [caps (:server-capabilities (client/info client))]
                              :when (and (= "2026-07-28" (:version (client/info client)))
                                         (some #(get-in caps [% "listChanged"]) ["tools" "prompts" "resources"]))]
                          [b (client/listen client
                                            {"toolsListChanged" true "promptsListChanged" true "resourcesListChanged" true}
                                            (fn [{:strs [method]}]
                                              (when (re-find #"/list_changed$" (str method))
                                                (request-resync! b))))]))
          listeners (mapv second followed)]
      ;; Return once each backend has acknowledged its subscription: a change
      ;; before that would not be announced. A change between listing the
      ;; backend and the acknowledgement is caught by one resync.
      (doseq [[b l] followed]
        (when (deref (:acknowledged l) 5000 nil)
          (request-resync! b)))
      {:server srv
       :close! (fn []
                 (doseq [l listeners] ((:stop! l)))
                 (doseq [{:keys [client]} backends] (client/close client)))})))
