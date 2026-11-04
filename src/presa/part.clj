(ns presa.part
  "Servers composed from parts.

  A part is plain data, every key optional:

    {:tools [...] :resources [...] :resource-templates [...] :prompts [...]
     :instructions \"...\" :close! (fn [])}

  tools, resources, resource templates and prompts as presa.server/server
  takes them. A library hands back a part; a caller combines parts into one
  server. :close! lets a part with live state (subprocesses, open files)
  settle when the server stops.

  `server` builds a server from parts, `serve!` serves it over stdio, and
  `with-client` connects a client to it in the same process, which is how
  code next to the server calls the tools an agent sees."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [presa.client :as client]
            [presa.protocol :as protocol]
            [presa.server :as server]
            [presa.transport.stdio :as stdio]))

(defn- duplicates [xs] (keep (fn [[x n]] (when (> n 1) x)) (frequencies xs)))

(defn combine
  "One part from parts (nils are skipped): their tools, resources, resource
  templates and prompts concatenated in order, their instructions joined by
  a blank line, and a :close! that closes each part and returns the results
  by position; a :close! that throws gives {:error message} in its place.
  A duplicate tool or prompt name, resource URI or URI template throws."
  [parts]
  (let [parts (vec (remove nil? parts))
        joined (fn [k] (vec (mapcat k parts)))
        p {:tools (joined :tools)
           :resources (joined :resources)
           :resource-templates (joined :resource-templates)
           :prompts (joined :prompts)}]
    (doseq [[what k id] [["tool" :tools :name] ["resource" :resources :uri]
                         ["resource template" :resource-templates :uri-template] ["prompt" :prompts :name]]]
      (when-let [d (seq (duplicates (map id (get p k))))]
        (throw (ex-info (str "Duplicate " what " " (str/join ", " d) "; see presa.part/prefix")
                        {:kind :invalid-input k (vec d)}))))
    (assoc p
           :instructions (some->> (keep :instructions parts) seq (str/join "\n\n"))
           :close! (fn []
                     (mapv (fn [part]
                             (when-let [f (:close! part)]
                               (try (f) (catch Exception e {:error (or (ex-message e) (str (class e)))}))))
                           parts)))))

(defn prefix
  "part with each tool and prompt name prefixed with p and _: (prefix part
  \"agent\") names the tool spawn agent_spawn."
  [part p]
  (let [rename (fn [xs] (mapv #(update % :name (fn [n] (str p "_" n))) xs))]
    (cond-> part
      (:tools part) (update :tools rename)
      (:prompts part) (update :prompts rename))))

(defn server
  "[server part]: a presa server of the combined :parts, and that part, for
  its :close!. Other options are presa.server/server's; :name defaults to
  the working directory's name, :version to \"0\", and :versions to every
  revision presa serves (presa.protocol/versions), so initialize-era
  clients such as Codex connect. Explicit :instructions replace the parts'."
  [{:keys [parts] :as opts}]
  (let [p (combine parts)]
    [(server/server (merge {:name (.getName (.getCanonicalFile (io/file ".")))
                            :version "0"
                            :versions protocol/versions}
                           (select-keys p [:tools :resources :resource-templates :prompts])
                           (when (:instructions p) {:instructions (:instructions p)})
                           (dissoc opts :parts)))
     p]))

(defn serve!
  "Serve the parts over stdio until stdin closes, then close them and return
  what their :close! functions returned. opts are `server`'s plus
  presa.transport.stdio/serve!'s :in, :out and :log."
  [opts]
  (let [[srv p] (server (dissoc opts :in :out :log))]
    (try (stdio/serve! srv (select-keys opts [:in :out :log]))
         (catch Throwable e ((:close! p)) (throw e)))
    ((:close! p))))

(defn with-client
  "Call f with a presa client connected in process to the server opts
  describe (see `server`); afterwards close the client and the parts.
  Returns what f returns. connect-opts go to presa.client/connect."
  ([opts f] (with-client opts {} f))
  ([opts connect-opts f]
   (let [[srv p] (server opts)]
     (try
       (let [c (client/connect (client/in-process srv) connect-opts)]
         (try (f c) (finally (client/close c))))
       (finally ((:close! p)))))))

(defn call
  "Call tool on client c with arguments: its structured content when it has
  some, else its text. An isError result throws ex-info with :tool and
  :result."
  ([c tool] (call c tool {}))
  ([c tool arguments]
   (let [r (client/call-tool c tool arguments)]
     (when (get r "isError")
       (throw (ex-info (str tool ": " (client/text r)) {:kind :tool-error :tool tool :result r})))
     (if (contains? r "structuredContent")
       (get r "structuredContent")
       (client/text r)))))
