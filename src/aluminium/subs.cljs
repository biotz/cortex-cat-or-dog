(ns aluminium.subs
  (:require [re-frame.core :as re-frame]))

(re-frame/reg-sub
  ::loading?
  (fn [db]
    (:loading? db)))

(re-frame/reg-sub
  ::classification-job
  (fn [db]
    (:classification-job db)))

(re-frame/reg-sub
  ::classification-result
  (fn [db]
    (:classification-result db)))
