(ns aluminium.domain.util
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn read-edn-resource
  "Read `edn-file` file from a resource folder."
  [edn-file]
  (->> edn-file
       io/resource
       slurp
       (edn/read-string {:readers *data-readers*})))
