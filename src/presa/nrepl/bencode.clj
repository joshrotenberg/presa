(ns presa.nrepl.bencode
  "Bencode, the wire format of nREPL: integers, byte strings, lists and
  dictionaries. Strings are written and read as UTF-8, which is what nREPL
  servers send; keywords are written as their names."
  (:import (java.io ByteArrayOutputStream EOFException InputStream OutputStream)))

(defn- utf8 ^bytes [^String s] (.getBytes s "UTF-8"))

(defn- write-bytes! [^OutputStream out ^bytes b] (.write out b 0 (alength b)))

(defn write!
  "Write x to out as bencode: an integer, a string, a keyword or symbol (as
  its name), a map with string or keyword keys, or a sequential collection.
  It does not flush."
  [^OutputStream out x]
  (cond
    (string? x) (let [b (utf8 x)]
                  (write-bytes! out (utf8 (str (alength b) ":")))
                  (write-bytes! out b))
    (or (keyword? x) (symbol? x)) (write! out (name x))
    (integer? x) (write-bytes! out (utf8 (str "i" x "e")))
    (map? x) (do (.write out (int \d))
                 (doseq [[k v] (sort-by first (map (fn [[k v]] [(if (string? k) k (name k)) v]) x))]
                   (write! out k)
                   (write! out v))
                 (.write out (int \e)))
    (or (sequential? x) (set? x)) (do (.write out (int \l))
                                      (run! #(write! out %) x)
                                      (.write out (int \e)))
    :else (throw (ex-info (str "Cannot bencode " (pr-str x)) {:kind :invalid-input :value x}))))

(defn encode
  "x as bencoded bytes."
  ^bytes [x]
  (let [out (ByteArrayOutputStream.)]
    (write! out x)
    (.toByteArray out)))

(defn- read-byte ^long [^InputStream in]
  (let [b (.read in)]
    (when (neg? b) (throw (EOFException. "End of bencode stream")))
    b))

(defn- read-long
  "The digits up to the byte end, as a long; first is a byte already read."
  [^InputStream in first end]
  (loop [sb (StringBuilder.) b (long first)]
    (if (= b (long end))
      (parse-long (str sb))
      (recur (.append sb (char b)) (read-byte in)))))

(defn- read-string* [^InputStream in first]
  (let [n (long (read-long in first (int \:)))
        buf (byte-array n)]
    (loop [off 0]
      (when (< off n)
        (let [got (.read in buf off (- n off))]
          (when (neg? got) (throw (EOFException. "End of bencode stream")))
          (recur (+ off got)))))
    (String. buf "UTF-8")))

(declare read-value)

(defn- read-items [^InputStream in]
  (loop [acc []]
    (let [b (read-byte in)]
      (if (= b (long (int \e)))
        acc
        (recur (conj acc (read-value in b)))))))

(defn- read-value [^InputStream in b]
  (let [b (long b)]
    (cond
      (= b (long (int \i))) (read-long in (read-byte in) (int \e))
      (= b (long (int \l))) (read-items in)
      (= b (long (int \d))) (apply hash-map (read-items in))
      (<= (long (int \0)) b (long (int \9))) (read-string* in b)
      :else (throw (ex-info (str "Bad bencode byte " b) {:kind :invalid-input :byte b})))))

(defn read!
  "Read one bencoded value from in: a long, a string, a vector or a map with
  string keys. Returns nil at the end of the stream; a stream that ends
  inside a value throws EOFException."
  [^InputStream in]
  (let [b (.read in)]
    (when-not (neg? b)
      (read-value in b))))
