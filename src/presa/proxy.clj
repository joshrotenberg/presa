(ns presa.proxy
  "A server that fronts several MCP servers as one. Each backend is a
  presa.client connection; its tools and prompts appear under a prefix
  (\"<prefix>_<name>\"), its resources and resource templates under their own
  URIs. Calls are forwarded with the caller's progress token and log level;
  a backend's input_required is passed to the caller and its answers back,
  with the backend's requestState kept in the proxy's own signed state; a
  backend's list changes resync the proxy and reach the proxy's listeners.

  Not forwarded: log messages from backends (over stdio they cannot be
  matched to a request), and subscriptions to backend resources."
  (:require [presa.client :as client]
            [presa.server :as server]))

(defn- forward-progress [ctx]
  (fn [{:strs [progress total message]}]
    (server/progress! ctx progress (cond-> {} total (assoc :total total) message (assoc :message message)))))

(defn- call-opts [ctx]
  (cond-> {:input :return
           :on-progress (forward-progress ctx)
           ;; the backend asks for what the caller can answer
           :meta {"io.modelcontextprotocol/clientCapabilities" (or (:client-capabilities ctx) {})}}
    (:log-level ctx) (assoc :log-level (:log-level ctx))))

(defn- forward
  "Send method with params to c on behalf of the request of ctx. An
  input_required answer becomes the proxy's own, carrying the backend's
  requestState in the proxy's state; the retry brings the answers back."
  [c method params ctx]
  (let [state (:state ctx)
        params (cond-> params
                 (seq (:input-responses ctx)) (assoc "inputResponses" (:input-responses ctx))
                 (get state "backendState") (assoc "requestState" (get state "backendState")))
        result (client/request c method params (call-opts ctx))]
    (if (= "input_required" (get result "resultType"))
      (server/input-required (get result "inputRequests" {})
                             (cond-> {"proxied" true}
                               (contains? result "requestState") (assoc "backendState" (get result "requestState"))))
      result)))

(defn- strip [result]
  (dissoc result "resultType" "_meta" "ttlMs" "cacheScope"))

(defn- tool-defs [prefix sep c]
  (for [{:strs [name title description inputSchema outputSchema annotations]} (client/list-tools c)]
    (cond-> {:name (str prefix sep name)
             :input-schema inputSchema
             :handler (fn [arguments ctx]
                        (let [r (forward c "tools/call" {"name" name "arguments" arguments} ctx)]
                          (if (contains? r ::server/input-required) r (strip r))))}
      title (assoc :title title)
      description (assoc :description description)
      outputSchema (assoc :output-schema outputSchema)
      annotations (assoc :annotations annotations))))

(defn- complete-fns [c ref arg-names]
  (into {} (for [a arg-names]
             [a (fn [value others _]
                  (client/complete c ref a value others))])))

(defn- prompt-defs [prefix sep c]
  (for [{:strs [name title description arguments]} (client/list-prompts c)]
    (cond-> {:name (str prefix sep name)
             :arguments (mapv (fn [{:strs [name description required]}]
                                (cond-> {:name name}
                                  description (assoc :description description)
                                  (some? required) (assoc :required required)))
                              arguments)
             :handler (fn [args ctx] (let [r (forward c "prompts/get" {"name" name "arguments" args} ctx)]
                                       (if (contains? r "messages") (strip r) r)))
             :complete (complete-fns c {"type" "ref/prompt" "name" name} (map #(get % "name") arguments))}
      title (assoc :title title)
      description (assoc :description description))))

(defn- read-handler [c uri-of]
  (fn [& args]
    (let [ctx (last args)
          r (forward c "resources/read" {"uri" (uri-of ctx)} ctx)]
      (if (contains? r "contents") (get r "contents") r))))

(defn- resource-defs [c]
  (for [{:strs [uri name title description mimeType size]} (client/list-resources c)]
    (cond-> {:uri uri :name name :handler (read-handler c (constantly uri))}
      title (assoc :title title) description (assoc :description description)
      mimeType (assoc :mime-type mimeType) size (assoc :size size))))

(defn- template-defs [c]
  (for [{:strs [uriTemplate name title description mimeType]} (client/list-resource-templates c)]
    (cond-> {:uri-template uriTemplate :name name
             :handler (read-handler c :uri)
             :complete (complete-fns c {"type" "ref/resource" "uri" uriTemplate}
                                     (map second (re-seq #"\{\+?([A-Za-z0-9_.]+)\}" uriTemplate)))}
      title (assoc :title title) description (assoc :description description)
      mimeType (assoc :mime-type mimeType))))

(defn- has? [c capability] (contains? (:server-capabilities (client/info c)) capability))

(defn- catalog
  "The definitions a backend contributes."
  [{:keys [prefix client separator] :or {separator "_"}}]
  {:tools (if (has? client "tools") (vec (tool-defs prefix separator client)) [])
   :prompts (if (has? client "prompts") (vec (prompt-defs prefix separator client)) [])
   :resources (if (has? client "resources") (vec (resource-defs client)) [])
   :templates (if (has? client "resources") (vec (template-defs client)) [])})

(defn- check-unique! [kind ks]
  (when-let [dups (seq (for [[k n] (frequencies ks) :when (> n 1)] k))]
    (throw (ex-info (str "Backends share " kind ": " (vec dups)) {:kind :invalid-input kind (vec dups)}))))

(defn- sync!
  "Bring srv's definitions from backend in line with what it lists now."
  [srv previous backend]
  (let [now (catalog backend)
        swap-defs (fn [k key-fn add! remove!]
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
  sets :list-changed itself. Returns {:server srv :close! f}: serve srv on
  any transport, and (close!) stops following the backends' changes and
  closes their clients.

  Prefixed tool and prompt names, resource URIs and template URIs must not
  collide across backends."
  [backends opts]
  (let [backends (vec (map-indexed (fn [i b] (assoc b ::index i)) backends))
        catalogs (atom (into {} (map (fn [b] [(::index b) (catalog b)])) backends))
        all (fn [k] (mapcat k (vals @catalogs)))]
    (check-unique! :tools (map :name (all :tools)))
    (check-unique! :prompts (map :name (all :prompts)))
    (check-unique! :resources (map :uri (all :resources)))
    (check-unique! :resource-templates (map :uri-template (all :templates)))
    (let [srv (server/server (merge opts {:list-changed true
                                          :tools (vec (all :tools))
                                          :prompts (vec (all :prompts))
                                          :resources (vec (all :resources))
                                          :resource-templates (vec (all :templates))}))
          resync! (fn [b]
                    ;; sync! changes srv, so it runs once, outside swap!
                    (try (let [now (sync! srv (get @catalogs (::index b)) b)]
                           (swap! catalogs assoc (::index b) now))
                         (catch Exception _ nil)))
          listeners (vec (for [{:keys [client] :as b} backends
                               :let [caps (:server-capabilities (client/info client))]
                               :when (and (= "2026-07-28" (:version (client/info client)))
                                          (some #(get-in caps [% "listChanged"]) ["tools" "prompts" "resources"]))]
                           (client/listen client
                                          {"toolsListChanged" true "promptsListChanged" true "resourcesListChanged" true}
                                          (fn [{:strs [method]}]
                                            (when (re-find #"/list_changed$" (str method))
                                              (future (resync! b)))))))]
      {:server srv
       :close! (fn []
                 (doseq [l listeners] ((:stop! l)))
                 (doseq [{:keys [client]} backends] (client/close client)))})))
