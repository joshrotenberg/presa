(ns ^:no-doc presa.server.core
  "Internal to presa.server; not part of the API. Errors, results and the
  markers handlers return, events, and what a handler sends the client."
  (:require [clojure.data.json :as json]
            [clojure.walk :as walk]
            [presa.content :as content]
            [presa.input :as input]
            [presa.jsonrpc :as rpc]
            [presa.protocol :as protocol]
            [presa.schema :as schema]))

(defn error
  "An exception that becomes a JSON-RPC error response when a handler throws
  it. code is a key of presa.protocol/error-codes or an integer."
  ([code message] (error code message nil))
  ([code message data]
   (ex-info message {:presa/error {:code code :message message :data data}})))

;; Errors and results

(defn json-text [v]
  (json/write-str v :escape-slash false :escape-unicode false))

(defn as-json
  "v as it reads back from JSON: string keys, vectors, no keywords; nil
  stays nil. requestState goes through this on every path, as it travels
  in a token on 2026-07-28."
  [v]
  (when (some? v) (json/read-str (json-text v))))

(defn text
  "A tool result with one text content block."
  [s]
  {"content" [(content/text s)] "isError" false})

(defn structured
  "A tool result carrying the map m as structuredContent, and as JSON text
  for clients that read only content."
  [m]
  {"content" [(content/text (json-text m))]
   "structuredContent" m
   "isError" false})

(defn content
  "A tool result from a sequence of content blocks (see presa.content)."
  [blocks]
  {"content" (vec blocks) "isError" false})

(defn tool-error
  "A tool result that reports a failure to the model: isError true, with
  message as its text. Use this for errors the model can act on; `error` is
  for a request the server could not take."
  [message]
  {"content" [(content/text message)] "isError" true})

(defn emit!
  "Hand event to the server's :on-event function, if it has one. A failing
  function never fails the request."
  [srv event]
  (when-let [f (:on-event srv)]
    (try (f event) (catch Throwable _ nil))))

(defn tool-result
  "A handler's return value as a tools/call result. A string becomes text, a
  map with \"content\" is a result already, any other map is structured
  content, nil is an empty result, and anything else becomes JSON text. A
  map's keyword keys become strings first, as the JSON writer would write
  them, so an output schema checks what is sent."
  [v]
  (cond
    (map? v) (let [v (walk/stringify-keys v)]
               (if (contains? v "content") (merge {"isError" false} v) (structured v)))
    (string? v) (text v)
    (nil? v) {"content" [] "isError" false}
    :else (text (json-text v))))

(defn input-required
  "What a tool, resource or prompt handler returns to ask the client for
  input before it can finish. requests maps ids of the server's choosing to
  input requests (see presa.input). state is an optional JSON value the
  server gets back with the answers, signed so the client cannot change it.

  The handler is called again with the answers: (:input-responses context)
  maps the same ids to the client's results, and (:state context) holds
  state. Answers can be partial; ask again for what is missing. On
  2026-07-28 this becomes an input_required result the client answers on a
  new request; on an initialize-era session presa sends the requests to the
  client itself and calls the handler again on the same request."
  ([requests] (input-required requests nil))
  ([requests state]
   (when-not (and (map? requests) (every? string? (keys requests))
                  (every? #(input/request-methods (get % "method")) (vals requests)))
     (throw (ex-info "input-required takes a map of string ids to input requests"
                     {:kind :invalid-input :requests requests})))
   {:presa.server/input-required {:requests requests :state state}}))

(defn input-response
  "The client's answer to the input request with id, or nil."
  [context id]
  (get (:input-responses context) id))

(defn input-required? [v] (and (map? v) (contains? v :presa.server/input-required)))

(defn task-created? [v] (and (map? v) (contains? v :presa.server/task-created)))

(defn now-ms [] (System/currentTimeMillis))

(defn check-output!
  "Throw when a tool's result breaks its output schema."
  [srv tool result]
  (when (and (:validate? srv) (:output-schema tool) (not (get result "isError")))
    (if-not (contains? result "structuredContent")
      (throw (error :internal-error "Tool omitted structured output required by its output schema"))
      (when-let [invalid ((:validator srv) (get result "structuredContent") (:output-schema tool))]
        (throw (error :internal-error (str "Tool output failed schema validation " (schema/describe invalid))))))))

(defn error-code [code]
  (if (keyword? code) (get protocol/error-codes code) code))

;; Notifications from handlers

(defn notify!
  "Send the client a notification while the request of context runs."
  [context method params]
  ((:notify context) (rpc/notification method params)))

(def client-request-capabilities
  {"roots/list" "roots"
   "sampling/createMessage" "sampling"
   "elicitation/create" "elicitation"})

(defn client-request!
  "Send the client a request from the handler of an initialize-era request,
  and return the result map: roots/list, sampling/createMessage,
  elicitation/create, or ping. Throws ex-info with :kind :protocol for an
  error response, :kind :unsupported when the client did not declare the
  capability the method needs, or the revision has no requests from server
  to client (2026-07-28 asks for input with input_required results instead),
  :kind :timeout, or :kind :closed.

  opts: :timeout-ms (default 60000)."
  ([context method params] (client-request! context method params {}))
  ([context method params {:keys [timeout-ms] :or {timeout-ms 60000}}]
   (when (or (protocol/stateless? (:version context)) (nil? (:request context)))
     (throw (ex-info (str "This connection cannot send " method " to the client")
                     {:kind :unsupported :method method})))
   (when-let [capability (get client-request-capabilities method)]
     (when-not (contains? (:client-capabilities context) capability)
       (throw (ex-info (str "The client did not declare the " capability " capability")
                       {:kind :unsupported :method method :capability capability}))))
   (let [params (if (and (= "2025-06-18" (:version context)) (= "elicitation/create" method)
                         (= "form" (get params "mode")))
                  (dissoc params "mode")
                  params)
         response ((:request context) method params timeout-ms)]
     (cond
       (= :presa.connection/closed response)
       (throw (ex-info "The connection closed before the client answered" {:kind :closed :method method}))

       (contains? response "error")
       (let [{:strs [code message data]} (get response "error")]
         (throw (ex-info (str "MCP error " code ": " message)
                         {:kind :protocol :code code :message message :data data})))

       :else (get response "result")))))
