(ns aluminium.domain.db-conn
  (:require [aluminium.domain.util :as util]
            [clojure.string :as str]
            [datomic.api :as d]
            [duct.logger :refer [log]]
            [integrant.core :as ig]))

(defn- transact-schema?
  "Transact the Datomic schema if not already defined.
  Check whether the Datomic schema defined by file/resource `db-schema`
  is already defined in the database where `conn` points to. If the
  schema is not defined yet, execute a transaction to define it,
  and log that fact to `logger`."
  [conn db-schema logger]
  ;; Quick hack to check whether the job entity is already defined in
  ;; the schema. We should be using something like io.rkn/conformity,
  ;; but it doesn't support Datomic Client API yet.
  (let [db (d/db conn)
        job-upload-url-attrib-q '{:find [?v]
                                  :in [$]
                                  :where [[$ _ :db.install/attribute ?v]
                                          [$ ?v :db/ident :job/upload-url]]}
        result (d/q job-upload-url-attrib-q db)
        job-upload-url-attrib (map #(d/pull db '[*] (first %)) result)]
    (if (empty? job-upload-url-attrib)
      (let [schema (util/read-edn-resource db-schema)]
        (log logger :report ::transacting-schema [db-schema])
        (d/transact conn schema)
        (log logger :report ::schema-transacted [])))))

;; Setup the connection to the Datomic peer server (used by the Client API)
(defmethod ig/init-key :aluminium.domain/db-conn [_ {:keys [datomic-cfg logger] :as options}]
  (let [peer-cfg (:peer-api-config datomic-cfg)
        endpoint (:endpoint peer-cfg)
        endpoint (if (str/ends-with? endpoint "/") endpoint (str endpoint "/"))
        password (str "?password=" (:password peer-cfg))
        db-name (:db-name datomic-cfg)
        db-uri (str endpoint db-name password)
        db-schema (:db-schema datomic-cfg)]
    (log logger :report ::starting-connection [(:endpoint peer-cfg) db-name])
    (try
      (let [conn (d/connect db-uri)]
        (transact-schema? conn db-schema logger)
        (log logger :report ::connection-started [])
        ;; Also return the logger, so we can use it the halt-key! multimethod
        ;; Duct will pass the returned map to halt-key! method.
        {:logger logger, :conn conn})
      (catch Exception e
        ;; The reason for the exception may be that the database
        ;; doesn't exist yet. So try to create it, and if we succeed,
        ;; try to connect to it again.
        (log logger :report ::creating-database [(:endpoint peer-cfg) db-name])
        (try
          (if-not (d/create-database db-uri)
            ;; Nope, the database already exist, so the source for
            ;; the connection error might be other. So error out!
            (throw (ex-info "Datomic connection failure" {:cause (.getMessage e)})))
          ;; The database should now exist, let's try connectig to it again.
          (let [conn (d/connect db-uri)]
            (log logger :report ::database-created [])
            (transact-schema? conn db-schema logger)
            (log logger :report ::connection-started [])
            {:logger logger, :conn conn})
          (catch Exception e
            (throw (ex-info "Datomic connection failure" {:cause (.getMessage e)}))))))))

;; Destroy the connection to the Datomic peer server, used by the Client API.
(defmethod ig/halt-key! :aluminium.domain/db-conn [_ {:keys [conn logger] :as options}]
  (log logger :report ::stopping-connection))
