(ns presa.input
  "Input requests: what a server asks the client for in the middle of a
  request (elicitation, sampling, roots), as bare request maps
  {\"method\" .. \"params\" ..}, and the client capabilities each one needs.

  On 2026-07-28 these travel inside an input_required result (see
  presa.server/input-required); on initialize-era connections presa sends
  them to the client as requests.")

(defn elicit
  "A form elicitation: message for the user, and requested-schema, a flat
  object schema of primitive properties."
  [message requested-schema]
  {"method" "elicitation/create"
   "params" {"mode" "form" "message" message "requestedSchema" requested-schema}})

(defn elicit-url
  "A URL elicitation: ask the user to visit url (an http or https URL)."
  [message url]
  {"method" "elicitation/create"
   "params" {"mode" "url" "message" message "url" url}})

(defn sample
  "A sampling request: ask the client's model for a message. messages are
  maps with \"role\" and \"content\". opts: :system-prompt, :temperature,
  :stop-sequences, :model-preferences, :include-context, :metadata."
  ([messages max-tokens] (sample messages max-tokens {}))
  ([messages max-tokens {:keys [system-prompt temperature stop-sequences model-preferences
                                include-context metadata]}]
   {"method" "sampling/createMessage"
    "params" (cond-> {"messages" (vec messages) "maxTokens" max-tokens}
               system-prompt (assoc "systemPrompt" system-prompt)
               temperature (assoc "temperature" temperature)
               stop-sequences (assoc "stopSequences" stop-sequences)
               model-preferences (assoc "modelPreferences" model-preferences)
               include-context (assoc "includeContext" include-context)
               metadata (assoc "metadata" metadata))}))

(defn roots
  "A request for the client's roots."
  []
  {"method" "roots/list" "params" {}})

(def request-methods
  "The methods an input request may have."
  #{"elicitation/create" "sampling/createMessage" "roots/list"})

(defn- needs
  "The client capabilities one input request needs, as a capabilities map."
  [{:strs [method params]}]
  (case method
    "elicitation/create" (if (= "url" (get params "mode"))
                           {"elicitation" {"url" {}}}
                           {"elicitation" {"form" {}}})
    "sampling/createMessage" (cond-> {"sampling" {}}
                               (or (contains? params "tools") (contains? params "toolChoice"))
                               (assoc-in ["sampling" "tools"] {})
                               (not (contains? #{nil "none"} (get params "includeContext")))
                               (assoc-in ["sampling" "context"] {}))
    "roots/list" {"roots" {}}
    {}))

(defn- has? [capabilities [top sub]]
  (let [declared (get capabilities top)]
    (and (map? declared)
         (or (nil? sub)
             (contains? declared sub)
             ;; an empty elicitation capability means form support
             (and (= ["elicitation" "form"] [top sub]) (empty? declared))))))

(defn missing-capabilities
  "The capabilities that requests (a map of id to request, or a sequence of
  requests) need and capabilities lacks, merged into one capabilities map,
  or nil when nothing is missing."
  [requests capabilities]
  (let [requests (if (map? requests) (vals requests) requests)
        missing (for [request requests
                      [top subs] (needs request)
                      path (if (empty? subs) [[top nil]] (map (fn [[sub _]] [top sub]) subs))
                      :when (not (has? capabilities path))]
                  path)]
    (when (seq missing)
      (reduce (fn [acc [top sub]]
                (if sub
                  (assoc-in acc [top sub] {})
                  (update acc top #(or % {}))))
              {}
              missing))))

(defn supported?
  "True when the client declared what request needs."
  [request capabilities]
  (nil? (missing-capabilities [request] capabilities)))
