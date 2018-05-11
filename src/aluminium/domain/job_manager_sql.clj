(ns aluminium.domain.job-manager-sql
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [duct.database.sql])
  (:import java.util.UUID))

(defn- gen-id
  "Generate a unique job ID, using UUIDs."
  []
  (UUID/randomUUID))

(defn- str-to-id
  "Convert `id` (a UUID string representation) into a real UUID value."
  [id]
  {:pre [(string? id)]}
  (try
    (UUID/fromString id)
    (catch IllegalArgumentException e
      ;; The ID value does not conform to the string representation of an UUID.
      nil)))

(def ^:private convert-identifiers-option
  "Option map specifying how to convert ResultSet column names to keywords.
  It defaults to clojure.string/lower-case, but our keywords include
  hyphens instead of underscores. So we need to convert SQL
  underscores to hyphens in our keyworks."
  {:identifiers #(string/lower-case (string/replace % \_ \-))})

(def ^:private convert-entities-option
  "Option map specifying how to convert Clojure keywords/string to SQL
  entity names. It defaults to identity, but our keywords include
  hyphens, which are invalid characters in SQL column names. So we
  change them to underscores."
  {:entities #(string/replace % \- \_)})

(declare get-job-sql)

(defn- create-or-update-job-sql
  "Given the connection `db` to database, perform a query to
  create a new job if `id` is nil, or update an existing job
  identified by `id`. The values of the job to created/updated are
  specified in the `job-values` map, with the following possible keys:

  :upload-url, :image-path, :status and :details

  Only specified keys will be used, and no defaults will be applied.

  It returns the `id` of the created/updated job."
  [db id {:keys [upload-url image-path status details] :as job-values}]
  {:pre [(or (nil? id) (uuid? id))]}
  (let [insert (nil? id)
        id (or id (gen-id))
        job (cond-> {:id id}
              upload-url (conj {:upload-url upload-url})
              image-path (conj {:image-path image-path})
              status (conj {:status status})
              details (conj {:details details}))]
    (if insert
      (jdbc/insert! db :jobs job convert-entities-option)
      (jdbc/update! db :jobs job ["id = ?" id] convert-entities-option))
    id))

(defn- create-job-sql
  "Creat a new job using `db` connection. The values of the job to
  created are specified using the `job-values` map, with the following
  keys:

  :upload-url, :image-path, :status and :details

  If `:status` is not specified, it defaults to `:job.status/SUBMITTED`.
  If `:details` is not specified, it defaults to an empty string.

  It returns the `id` of the created job. Throws `ExceptionInfo` if any of
  the mandatory values of the job is not specified."
  [db job-values]
  (let [mandatory-values #{:upload-url :image-path}
        existing-values (set (keys job-values))
        missing-mandatory-values (clojure.set/difference mandatory-values existing-values)]
    (if-not (empty? missing-mandatory-values)
      (throw (ex-info "Mandatory job values not specified" {:details missing-mandatory-values}))
      (let [status (or (:status job-values) :job.status/SUBMITTED)
            details (or (:details job-values) "")
            job-values (-> job-values
                           (assoc :status status)
                           (assoc :details details))]
        (create-or-update-job-sql db nil job-values)))))

(defn- jobs-query
  "Get the jobs identified by `query` from database, using `db` connection."
  [db query]
  (let [rows (jdbc/query db query convert-identifiers-option)]
    ;; Make sure we convert the ´status´ value back into an actual keyword.
    (map (fn [row] (update row :status read-string)) rows)))

(defn- get-job-sql
  "Try to retrieve an existing job identified by `id` from database,
  using `db` connection. If the job is not found, returns NIL."
  [db id]
  (let [id (if (uuid? id) id (str-to-id id))
        job (jobs-query db ["SELECT * FROM jobs WHERE id = ?" id])]
    ;; There should be at most one row, so pick the first one (still
    ;; works if we don't get any rows back).
    (first job)))

(defn- get-job-by-upload-url-sql
  "Try to retrieve an existing job whose upload url is `upload-url`
  from database, using `db` connection. If the job is not found,
  returns NIL."
  [db upload-url]
  (let [job (jobs-query db ["SELECT * FROM jobs WHERE upload_url = ?" upload-url])]
    ;; There should be at most one row, so pick the first one (still
    ;; works if we don't get any rows back).
    (first job)))

(defn- get-jobs-sql
  "Retrieves all existing jobs from database, using `db` connection."
  [db]
  (jobs-query db ["SELECT * FROM jobs"]))

(defn- update-job-sql
  "Updates an existing job identified by `id`, using `db`
  connection. The values to be updated are specified in the `updates`
  map, with the following possible keys:

  :upload-url, :image-path, :status and :details

  Returns the `id` of the updated job, or NIL if the job doesn't
  exist."
  [db id updates]
  (let [id (if (uuid? id) id (str-to-id id))
        job (get-job-sql db id)]
    (if job
      (create-or-update-job-sql db id updates)
      nil)))

(defn- delete-job-sql
  "Delete an existing job identified by `id` from database, using `db`
  connection. Returns `id` if the job was deleted, or NIL if the job
  didn't exist."
  [db id]
  (let [id (if (uuid? id) id (str-to-id id))
        job (get-job-sql db id)]
    (if job
      (jdbc/delete! db :jobs ["id = ?" id] convert-entities-option)
      id)))

(defn create-job
  [{db :spec} job-values]
  (create-job-sql db job-values))
(defn get-job
  [{db :spec} id]
  (get-job-sql db id))
(defn get-job-by-upload-url
  [{db :spec} upload-url]
  (get-job-by-upload-url-sql db upload-url))
(defn get-jobs
  [{db :spec}]
  (get-jobs-sql db))
(defn update-job
  [{db :spec} id job-updates]
  (update-job-sql db id job-updates))
(defn delete-job
  [{db :spec} id]
  (delete-job-sql db id))
