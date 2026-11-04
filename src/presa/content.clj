(ns presa.content
  "MCP content blocks as maps with string keys: what tool results, prompt
  messages and resource reads carry. Binary data is a byte array or a string
  that is already base64."
  (:import (java.util Base64)))

(defn base64
  "data as a base64 string: a byte array is encoded, a string is taken as
  base64 already."
  [data]
  (if (bytes? data)
    (.encodeToString (Base64/getEncoder) ^bytes data)
    (str data)))

(defn decode-base64
  "The bytes of a base64 string."
  ^bytes [^String s]
  (.decode (Base64/getDecoder) s))

(defn- with-optional [m opts]
  (cond-> m
    (:annotations opts) (assoc "annotations" (:annotations opts))
    (:meta opts) (assoc "_meta" (:meta opts))))

(defn text
  "A text content block."
  ([s] (text s {}))
  ([s opts] (with-optional {"type" "text" "text" (str s)} opts)))

(defn image
  "An image content block from bytes or base64 and a MIME type."
  ([data mime-type] (image data mime-type {}))
  ([data mime-type opts]
   (with-optional {"type" "image" "data" (base64 data) "mimeType" mime-type} opts)))

(defn audio
  "An audio content block from bytes or base64 and a MIME type."
  ([data mime-type] (audio data mime-type {}))
  ([data mime-type opts]
   (with-optional {"type" "audio" "data" (base64 data) "mimeType" mime-type} opts)))

(defn resource-contents
  "The contents of a resource at uri: text for a string, blob (base64) for a
  byte array. opts: :mime-type, :meta."
  ([uri data] (resource-contents uri data {}))
  ([uri data {:keys [mime-type meta]}]
   (cond-> {"uri" uri}
     mime-type (assoc "mimeType" mime-type)
     (bytes? data) (assoc "blob" (base64 data))
     (not (bytes? data)) (assoc "text" (str data))
     meta (assoc "_meta" meta))))

(defn embedded-resource
  "A content block embedding a resource's contents; see `resource-contents`."
  ([uri data] (embedded-resource uri data {}))
  ([uri data opts]
   (with-optional {"type" "resource" "resource" (resource-contents uri data opts)}
     (dissoc opts :meta))))

(defn resource-link
  "A content block linking to a resource the client can read. opts: :title,
  :description, :mime-type, :size, :annotations."
  ([uri name] (resource-link uri name {}))
  ([uri name {:keys [title description mime-type size] :as opts}]
   (with-optional (cond-> {"type" "resource_link" "uri" uri "name" name}
                    title (assoc "title" title)
                    description (assoc "description" description)
                    mime-type (assoc "mimeType" mime-type)
                    size (assoc "size" size))
     opts)))
