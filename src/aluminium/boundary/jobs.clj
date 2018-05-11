(ns aluminium.boundary.jobs)

(defrecord Job [id upload-url status details])

(defprotocol JobManagement
  (create-job [db job-values])
  (get-job [db id])
  (get-job-by-upload-url [db upload-url])
  (get-jobs [db])
  (update-job [db id job-updates])
  (delete-job [db id]))

