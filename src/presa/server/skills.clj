(ns ^:no-doc presa.server.skills
  "Internal to presa.server; not part of the API. The Skills extension
  (presa.skill): the server's skills indexed, and skills/list, skills/get
  and resources/directory/read."
  (:require [presa.skill :as skill]
            [presa.server.access :refer [check-permitted! permitted?]]
            [presa.server.core :refer [error]]
            [presa.server.shape :refer [paged]]))

(defn index
  "skills as the server keeps them: by SKILL.md URI and in order, their
  files as resources, and their directories. A skill nested in another
  shares files with it; each file is one resource."
  [skills]
  (let [uris (map skill/uri skills)]
    (when-not (apply distinct? ::none uris)
      (throw (ex-info "The skill paths must be distinct" {:kind :invalid-input :values (vec uris)})))
    {:by-uri (zipmap uris skills)
     :order (vec uris)
     :resources (->> (mapcat skill/resources skills)
                     (reduce (fn [[seen acc] r] (if (seen (:uri r)) [seen acc] [(conj seen (:uri r)) (conj acc r)]))
                             [#{} []])
                     second)
     :directories (apply merge-with (fn [a b] (vec (distinct (concat a b)))) {} (map skill/directories skills))}))

(defn skills-list [srv params context]
  (let [{:keys [by-uri order]} (:skills srv)]
    (paged srv "skills/list" params "skills"
           (->> order
                (filter #(permitted? srv context :resource %))
                (map (comp skill/entry by-uri))))))

(defn- uri-param [params method]
  (let [uri (get params "uri")]
    (when-not (string? uri)
      (throw (error :invalid-params (str method " requires a string uri"))))
    uri))

(defn skills-get [srv params context]
  (let [uri (uri-param params "skills/get")
        s (or (get-in srv [:skills :by-uri uri])
              (throw (error :invalid-params "Skill not found" {"uri" uri})))]
    (check-permitted! srv context :resource uri (str "skill " uri))
    {"skill" (skill/entry s)}))

(defn directory-read [srv params context]
  (let [uri (uri-param params "resources/directory/read")
        children (or (get-in srv [:skills :directories uri])
                     (throw (error :invalid-params "Directory not found" {"uri" uri})))]
    (paged srv "resources/directory/read" params "resources"
           (filter #(or (= skill/directory-mime-type (get % "mimeType"))
                        (permitted? srv context :resource (get % "uri")))
                   children))))
