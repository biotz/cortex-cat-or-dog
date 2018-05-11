(ns aluminium.handler.cat-or-dog
  "Handler for cat-or-dog routes"
  (:require [aluminium.domain.cortex :as alum-cortex]
            [aluminium.domain.job-manager-sql :as job-manager]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [compojure.core :refer :all]
            [duct.logger :refer [log]]
            [integrant.core :as ig]
            [org.akvo.resumed :as resumed]))

(def api-root-path
  "All handler endpoints hang from this local part."
  "/cat-or-dog")

(def classification-rel-path
  "Classification API endpoing hangs from this relative part."
  "/classify")

(def classification-full-path
  "Classification API endpoing hangs from this full part."
  (str api-root-path classification-rel-path))

(defn- build-error-details-from-exception
  "Build a map with details about the error that triggered the exception `e`."
  [e]
  {:pre [(instance? Exception e)]}
  (let [cause (or (.getMessage e) "Unknown error")
        details (or (ex-data e) {})]
    {:success false :details {:cause cause :additional-details details}}))

(defn process-image
  "Classify the image specified by `image-path`, using `cortex-nn` model.
  Update the job status with `id` identifier, in the database pointed to
  by `conn`, with the classification result. Log details to `logger`."
  [image-path cortex-nn conn id logger]
  ;; IMPORTANT: Beware of https://stuartsierra.com/2015/05/27/clojure-uncaught-exceptions
  ;;            Exceptions will not be thrown until we call deref, so wrap it all in try/catch
  (try
    (job-manager/update-job conn id {:status :job.status/CLASSIFYING})
    (if-let [result (alum-cortex/label-image image-path cortex-nn logger)]
      ;; As the details attribute for the job is a string, convert the result
      ;; to something that can be safely stored in a string, like JSON.
      (job-manager/update-job conn id {:status :job.status/DONE
                                       :details (json/write-str result)})
      (job-manager/update-job conn id {:status :job.status/ERROR
                                       :details (json/write-str {:reason :invalid-image-file})}))
    (catch Exception e
      ;; Nothing to do. Just catch the exception and log the details.
      (log logger :report ::classification-exception [(build-error-details-from-exception e)]))))

(defn- enqueue-classification-job
  "Create a new job to asynchronously classify the image uploaded at `upload-url`.
  The uploaded image is expected to be stored somewhere under
  `save-dir`. Use `cortex-nn` model for the classification job, and store
  all job details in the database pointed to by `conn`. Log details to
  `logger`."
  [conn upload-url save-dir cortex-nn logger]
  ;; resumed/file-for-upload throws an exception if the upload URL doesn't
  ;; conform to its own spec, or the id doesn't point to an already
  ;; uploaded file, so beware!
  (try
    (if-let [image-path (resumed/file-for-upload save-dir upload-url)]
      (let [result {:success true :details {}}
            existing-job (job-manager/get-job-by-upload-url conn upload-url)]
        (if-not existing-job
          (if-let [id (job-manager/create-job conn {:upload-url upload-url,
                                                    :image-path (str image-path)})]
            (do
              ;; Launch the heavy processing process in a separate thread.
              ;; Don't wait for it to finish.
              (future (process-image image-path cortex-nn conn id logger))
              (assoc-in result [:details :id] id)))
          (assoc-in result [:details :id] (:id existing-job))))
      (throw (ex-info "File does not exist" {:upload-url upload-url})))
    (catch Exception e
      (build-error-details-from-exception e))))

(defn- get-job-from-queue
  "Get classification job identified by `id`, from the database pointed to by `conn`."
  [conn id]
  (try
    (if-let [job (job-manager/get-job conn id)]
      (let [status (:status job)
            details-json (:details job)
            details (json/read-str details-json :eof-error? false :eof-value {} :key-fn keyword)
            job {:id id :status status :details details}]
        job)
      nil)
    (catch Exception e
      (build-error-details-from-exception e))))

(defn- remove-job-from-queue
  "Remove classification job identified by `id`, from the database pointed to by `conn`."
  [conn id]
  (try
    (job-manager/delete-job conn id)
    (catch Exception e
      (build-error-details-from-exception e))))

(defn- classification-job-status
  "Get classification job status and build HTTP response.
  The classification job is identified by `id`, and retrieved from the
  database pointed to by `conn`. If the job doesn't exist, NIL is
  returned.

  If the classification job has completed (successfully or not) a 200
  HTTP status is returned. Otherwise, a 202 HTTP status is
  returned. The body of the response contains the job status details.

  If the optional parameter `remove-completed` is truthy and the job
  is completed, it is removed from the database."
  [conn id & [remove-completed]]
  (if-let [job (get-job-from-queue conn id)]
    (let [body job
          status (:status job)
          completed-status #{:job.status/ERROR, :job.status/DONE}
          status (if (contains? completed-status status) 200 202)]
      (if remove-completed
        (remove-job-from-queue conn id)) ; Ignore possible errors during removal.
      {:status status, :body body})
    nil))

(defn- classify-post-handler
  "Handler for the HTTP POST method for the classification route.
  The route receives the `req` map with all the request values, and an
  `options` map with all the handler configuration options. It creates
  a new classification job to classify the image specified in the request
  body. If the classification job is created successfully, a 201 HTTP
  status is returned, with the Location header containing the URL
  where the job details can be queried.

  It there is a problem creating the job, a 400 HTTP status is returned."
  [{:keys [body-params] :as req} options]
  (let [save-dir (get-in options [:resumed :config :save-dir])
        conn (get-in options [:db-conn :conn])
        cortex-nn (get-in options [:cortex :nn])
        logger (:logger options)
        upload-url (:upload-url body-params)
        classification-job (enqueue-classification-job conn upload-url save-dir cortex-nn logger)
        id (get-in classification-job [:details :id])]
    (if (:success classification-job)
      {:status 201
       :headers {"Location" (str (resumed/get-location req) "/" id)}}
      {:status 400
       :body (:details classification-job)})))

(defn- classify-get-handler
  "Handler for the HTTP GET method for the classification route.
  The route receives the `id` of a classification job and an `options` map
  with all the handler configuration options. It queries the status of
  the classification job and a HTTP 2xx status is returned, with
  the job details in the response body.

  It the job doesn't exist, a 404 HTTP status is returned."
  [id options]
  (let [conn (get-in options [:db-conn :conn])]
    (if id
      (classification-job-status conn id)
      {:status 404, :body "Not found"})))

;; Handler definition for the managed routes.
(defmethod ig/init-key :aluminium.handler/cat-or-dog [_ options]
  (context api-root-path []
           (POST classification-rel-path req
                 (classify-post-handler req options))
           (GET [(str classification-rel-path "/:id"), :id #"[0-9a-f-]+"] req
                (classify-get-handler (get-in req [:params :id]) options))))
