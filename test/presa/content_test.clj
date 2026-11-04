(ns presa.content-test
  (:require [clojure.test :refer [deftest is testing]]
            [presa.content :as content]
            [presa.uri-template :as tpl]))

(deftest blocks
  (is (= {"type" "text" "text" "hi"} (content/text "hi")))
  (is (= {"type" "text" "text" "hi" "annotations" {"audience" ["user"]}}
         (content/text "hi" {:annotations {"audience" ["user"]}})))
  (is (= {"type" "image" "data" "AQI=" "mimeType" "image/png"} (content/image (byte-array [1 2]) "image/png")))
  (is (= {"type" "audio" "data" "UklGRg==" "mimeType" "audio/wav"} (content/audio "UklGRg==" "audio/wav")))
  (is (= {"type" "resource" "resource" {"uri" "x:/a" "mimeType" "text/plain" "text" "t"}}
         (content/embedded-resource "x:/a" "t" {:mime-type "text/plain"})))
  (is (= {"type" "resource" "resource" {"uri" "x:/b" "blob" "AQ=="}}
         (content/embedded-resource "x:/b" (byte-array [1]))))
  (is (= {"type" "resource_link" "uri" "x:/a" "name" "a" "mimeType" "text/plain"}
         (content/resource-link "x:/a" "a" {:mime-type "text/plain"})))
  (is (= [1 2 3] (vec (content/decode-base64 (content/base64 (byte-array [1 2 3])))))))

(deftest uri-templates
  (testing "parsing"
    (is (= ["people://" {:name "name" :reserved? false} "/profile"] (tpl/parse "people://{name}/profile")))
    (is (= ["name" "path"] (tpl/variables "x://{name}/{+path}")))
    (doseq [bad ["x://{a" "x://a}" "x://{a,b}" "x://{?q}"]]
      (is (= :invalid-input (:kind (ex-data (try (tpl/parse bad) (catch Exception e e))))) bad)))
  (testing "matching"
    (let [m (tpl/matcher "people://{name}/profile")]
      (is (= {"name" "ada"} (m "people://ada/profile")))
      (is (= {"name" "a b"} (m "people://a%20b/profile")))
      (is (nil? (m "people://a/b/profile")))
      (is (nil? (m "people://ada/other"))))
    (is (= {"path" "a/b/c.txt"} ((tpl/matcher "file:///{+path}") "file:///a/b/c.txt")))
    (is (= {} ((tpl/matcher "x://fixed") "x://fixed"))))
  (testing "expansion"
    (is (= "people://a%20b%2Fc/profile" (tpl/expand "people://{name}/profile" {"name" "a b/c"})))
    (is (= "file:///a/b%20c.txt" (tpl/expand "file:///{+path}" {"path" "a/b c.txt"})))))
