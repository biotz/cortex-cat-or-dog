(ns aluminium.handler.root-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [aluminium.handler.root :as root]))

(deftest smoke-test
  (testing "example page exists"
    (let [handler  (ig/init-key :aluminium.handler/root {})
          response (handler (mock/request :get "/"))]
      (is (= 200 (:status response)) "response ok"))))
