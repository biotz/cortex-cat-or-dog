(ns aluminium.handler.root
  (:require [compojure.core :refer :all]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [ring.util.response :as resp]))

(defmethod ig/init-key :aluminium.handler/root [_ options]
  (context "/" []
    (GET "/" []
      (io/resource "aluminium/handler/root/index.html"))))
