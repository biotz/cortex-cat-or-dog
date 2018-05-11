(ns aluminium.domain.job-manager
  (:require [aluminium.domain.db-conn :as db-conn]
            [datomic.api :as d])
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

(declare get-db-job)

(defn- create-or-update-job
  "Given the connection `conn` to Datomic, perform a transaction to
  create a new job if `id` is nil, or update an existing job
  identified by `id`. The values of the job to created/updated are
  specified in the `job-values` map, with the following possible keys:

  :upload-url, :image-path, :status and :details

  Only specified keys will be used, and no defaults will be applied.

  It returns the `id` of the created/updated job. Throws `ExceptionInfo`
  on error."
  [conn id {:keys [upload-url image-path status details] :as job-values}]
  {:pre [(or (nil? id) (uuid? id))]}
  ;; If we are updating the job, there is no need to retract the old
  ;; values; since all the values are :db.cardinality/one, Datomic
  ;; will automatically retract the previous ones (see
  ;; https://docs.datomic.com/on-prem/tutorial.html#implicit-retract)
  ;; So both creation and update can be implemented by the same
  ;; operations under this condition.
  (let [entity-ids (if id
                     (get-db-job conn id)
                     {:db/id "temp-job-id",
                      :job/details {:db/id "temp-details-id"}})
        id (or id (gen-id))
        job {:db/id (:db/id entity-ids)
             :job/id id}
        job (cond-> job
              upload-url (conj {:job/upload-url upload-url})
              image-path (conj {:job/image-path image-path})
              status (conj {:job/status status})
              details (conj {:job/details details}))]
    (d/transact conn [job])
    id))

(defn create-job
  "Creat a new job, trasacting a new Datomic fact using `conn`
  connection. The values of the job to created are specified using the
  `job-values` map, with the following keys:

  :upload-url, :image-path, :status and :details

  If `:status` is not specified, it defaults to `:job.status/SUBMITTED`.
  If `:details` is not specified, it defaults to an empty string.

  It returns the `id` of the created job. Throws `ExceptionInfo` if any of
  the mandatory values of the job is not specified."
  [conn job-values]
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
        (create-or-update-job conn nil job-values)))))

(defn- clean-db-metadata
  "Strip Datomic metadata from `job` and rename the keys to match the
  ones used externally."
  [job]
  (if (seq  job)
    {:id (:job/id job)
     :upload-url (:job/upload-url job)
     :image-path (:job/image-path job)
     :status (get-in job [:job/status :db/ident])
     :details (:job/details job)}
    nil))

(defn- get-db-job
  "Try to retrive an existing job identified by `id` from Datomic, using
  `conn` connection. Returns full job data. If the job is not found,
  returns NIL.

  This function keeps all Datomic metadata that is needed for other
  internal job management functions."
  [conn id]
  (let [id (if (uuid? id) id (str-to-id id))
        db (d/db conn)
        job-q '{:find [?job]
                :in [$ ?id]
                :where [[$ ?job :job/id ?id]]}
        result (d/q job-q db id)
        job (map #(d/pull db '[*] (first %)) result)]
    ;; There should at most one job, so return the head of the seq
    ;; (will be nil if we didn't find the job)
    (first job)))

(defn get-job
  "Try to retrive an existing job identified by `id` from Datomic,
  using `conn` connection. If the job is not found, returns NIL."
  [conn id]
  ((comp clean-db-metadata get-db-job) conn id)  )

(defn get-job-by-upload-url
  "Try to retrive an existing job whose upload url is `upload-url`
  from Datomic, using `conn` connection. If the job is not found,
  returns NIL."
  [conn upload-url]
  (let [db (d/db conn)
        job-q '{:find [?job]
                :in [$ ?upload-url]
                :where [[?job :job/upload-url ?upload-url]]}
        result (d/q job-q db upload-url)
        job (map #(d/pull db '[*] (first %)) result)]
    (if (first job)
      (clean-db-metadata (first job))
      nil)))

(defn get-jobs
  "Retrieves all existing jobs from Datomic, using `conn`
  connection."
  [conn]
  (let [db (d/db conn)
        job-q '{:find [?job]
                :in [$]
                :where [[?job :job/id]]}
        result (d/q job-q db)
        jobs (->> result
                  (map #(d/pull db '[*] (first %)))
                  (map #'clean-db-metadata))]
    jobs))

(defn update-job
  "Updates an existing job identified by `id`, trasacting a new Datomic
  fact using `conn` connection. The values to be updated are specified
  in the `updates` map, with the following possible keys:

  :upload-url, :image-path, :status and :details

  Returns the `id` of the updated job, or NIL if the job doesn't
  exist."
  [conn id updates]
  (let [id (if (uuid? id) id (str-to-id id))
        job (get-db-job conn id)]
    (if job
      (create-or-update-job conn id updates)
      nil)))

(defn delete-job
  "Delete an existing job identified by `id` from Datomic, using `conn`
  connection. Returns `id` if the job was deleted, or NIL if the job
  does't exist."
  [conn id]
  (let [id (if (uuid? id) id (str-to-id id))
        job (get-db-job conn id)]
    (if job
      ;; We can't use retractEntity directly to retract the job.
      ;; https://docs.datomic.com/on-prem/transactions.html#dbfn-retractentity
      ;; states that "Entities that are components of the given entity
      ;; are also recursively retracted". This means that if we use
      ;; retractEntity directly, we retract the value of the status
      ;; enums we refer to. Which means we retract the :ident value of
      ;; those enums, efectively retracting the ident values of all
      ;; the other existing jobs.
      ;;
      ;; So we first need to retract the job status value (thus
      ;; "breaking the reference" of the :job/status component entity
      ;; to the enum), and then *in a second different transaction*
      ;; retract the job entity.
      (let [tx-status [:db/retract (:db/id job) :job/status (get-in job [:job/status :db/ident])]
            tx-job [:db/retractEntity (:db/id job)]]
        (d/transact conn [tx-status])
        (d/transact conn [tx-job])
        id))))
