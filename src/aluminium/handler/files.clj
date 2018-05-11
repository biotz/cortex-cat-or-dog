(ns aluminium.handler.files
  (:require [compojure.core :refer [ANY]]
            [integrant.core :as ig]
            [org.akvo.resumed :as resumed]))

(defmethod ig/init-key :aluminium.handler/resumed [_ options]
  {:handler (resumed/make-handler options)
   :config options})

(defmethod ig/init-key :aluminium.handler/files [_ options]
  (ANY "/files*" req
       ((get-in options [:resumed :handler]) req)))
