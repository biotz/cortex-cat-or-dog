(ns aluminium.events
  (:require [day8.re-frame.http-fx]
            [re-frame.core :as re-frame]
            [aluminium.db :as db]
            [ajax.core :as ajax :refer (GET DELETE POST PUT)]
            [clojure.string :as s]))

(re-frame/reg-event-db
 ::initialize-db
 (fn  [_ _]
   db/default-db))

(re-frame/reg-event-db
  ::set-loading
  (fn [db [_ value]]
    (assoc db :loading? value)))

(re-frame/reg-event-db
  ::reset-classification-result
  (fn [db [_]]
    (dissoc db :classification-result)))

;; See https://github.com/JulianBirch/cljs-ajax/blob/master/docs/interceptors.md
;; for details on how interceptors work.
(def ^:private location-header->body
  (ajax/to-interceptor {:name "Location header to classification job url"
                        :response (fn [response]
                                    (reduced [(-> response
                                                  ajax.protocols/-status
                                                  ajax.core/success?)
                                              {:job-url 
                                               (ajax.protocols/-get-response-header response
                                                                                    "Location")}]))}))

;; API ENDPOINTS
(re-frame/reg-event-fx
  ::start-classification
  (fn [{:keys [db]} [_ {:keys [file-path]}]]
    {:http-xhrio {:method :post
                  :uri "/cat-or-dog/classify"
                  :params {:upload-url file-path}
                  :format (ajax/transit-request-format)
                  :response-format (ajax/transit-response-format)
                  :on-success [::handle-classification-job]
                  :on-failure [::classification-failed]
                  :interceptors [location-header->body]}}))

;; This function replaces `dispatch-n` that was intended for adding the new classification job to the app DB
;; and also triggering the function to check the results until it finishes. TODO: Check if this is better aproach.
(re-frame/reg-event-fx
  ::handle-classification-job
  (fn [{:keys [db]} [_ {:keys [job-url]}]]
    {:db (-> db
             (assoc :classification-job job-url))
     :dispatch [::get-classification-status {:job-url job-url}]}))


(re-frame/reg-event-db
  ::classification-failed
  (fn [db [_ error]]
    (-> db
        (assoc :classification-error error)
        (dissoc :loading?))))

;; TODO: Pass `on-success` event as parameter to leave the function cleaner??
(re-frame/reg-event-fx
  ::get-classification-status
  (fn [{:keys [db]} [_ {:keys [job-url]}]]
    {:http-xhrio {:method :get
                  :uri job-url
                  :format (ajax/transit-request-format)
                  :response-format (ajax/transit-response-format)
                  :on-success [::wait-for-classification-end]
                  :on-failure [::classification-failed]}}))


(re-frame/reg-event-fx
  ::wait-for-classification-end
  (fn [{:keys [db]} [_ {:keys [status details]}]]
    (let [probability (get-in details [:guess :prob])
          class (get-in details [:guess :class])
          classification-check-freq 1000
          classification-finished? (or (= status :job.status/DONE)
                                       (= status :job.status/ERROR))
          classification-job (:classification-job db)]
      {:db (if classification-finished?
             (cond-> db
                     :always
                     (dissoc :loading?)
                     (= status :job.status/DONE)
                     (assoc :classification-result {:probability probability :class class :status :ok})
                     (= status :job.status/ERROR)
                     (assoc :classification-result {:status :error})
                     ;; Reset register job if the status is end-type. TODO: Move this to another function.
                     :always
                     (dissoc :classification-job))
             db)
       :dispatch-later (if-not classification-finished?
                         [{:ms classification-check-freq
                           :dispatch [::get-classification-status {:job-url classification-job}]}])})))
