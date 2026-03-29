(ns borba.sql-client
  "Integrant component for PostgreSQL via next.jdbc + HikariCP.

   Registers :components/database with a pooled DataSource exposed
   as :sql-client in the component map.

   Wrapper fns provide sensible defaults (kebab-cased result maps,
   returning one vs. many) so callers don't need to import next.jdbc directly.

   Usage:
     (let [{:keys [sql-client]} components]
       (sql/execute-one! sql-client [\"SELECT * FROM users WHERE id = ?\" id]))"
  (:require [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection]
            [next.jdbc.result-set :as rs]
            [clojure.string :as str])
  (:import (com.zaxxer.hikari HikariDataSource)))

;; ── Default result-set builder ───────────────────────────────────────────────
;; Converts SQL column names to unqualified kebab-case keywords automatically.
;; e.g. "created_at" → :created-at, "user_id" → :user-id

(def ^:private default-opts
  {:builder-fn rs/as-unqualified-kebab-maps})

;; ── Integrant lifecycle ──────────────────────────────────────────────────────

(defmethod ig/init-key :components/database
  [_ {:keys [jdbc-url username password max-pool-size min-idle]}]
  (let [ds (connection/->pool
            HikariDataSource
            {:jdbcUrl           jdbc-url
             :username          username
             :password          password
             :maximumPoolSize   (or max-pool-size 10)
             :minimumIdle       (or min-idle 2)
             :connectionTimeout 30000
             :idleTimeout       600000
             :maxLifetime       1800000
             :poolName          "borba-pool"})]
    (println "🗄️  [sql-client] Pool started →" jdbc-url)
    ds))

(defmethod ig/halt-key! :components/database
  [_ datasource]
  (when (instance? HikariDataSource datasource)
    (.close ^HikariDataSource datasource)
    (println "🗄️  [sql-client] Pool stopped")))

;; ── Public API ───────────────────────────────────────────────────────────────

(defn execute!
  "Executes a SQL statement and returns a vector of result rows.
   SQL must be a vector: [\"SELECT ...\" param1 param2 ...]"
  ([ds sql]
   (jdbc/execute! ds sql default-opts))
  ([ds sql opts]
   (jdbc/execute! ds sql (merge default-opts opts))))

(defn execute-one!
  "Executes a SQL statement and returns a single row (or nil).
   SQL must be a vector: [\"SELECT ...\" param1]"
  ([ds sql]
   (jdbc/execute-one! ds sql default-opts))
  ([ds sql opts]
   (jdbc/execute-one! ds sql (merge default-opts opts))))

(defn insert!
  "Inserts a row into table and returns the inserted row.

   Example:
     (sql/insert! ds :users {:id #uuid \"...\" :name \"Ana\" :email \"ana@borba.com\"})"
  [ds table row]
  (jdbc/execute-one!
   ds
   (into [(str "INSERT INTO " (name table)
               " (" (str/join ", " (map name (keys row))) ")"
               " VALUES (" (str/join ", " (repeat (count row) "?")) ")"
               " RETURNING *")]
         (vals row))
   default-opts))

(defn update!
  "Updates rows matching where-clause and returns updated rows.

   Example:
     (sql/update! ds :users {:status \"active\"} {:id some-id})"
  [ds table set-map where-map]
  (let [set-clause   (str/join ", " (map #(str (name %) " = ?") (keys set-map)))
        where-clause (str/join " AND " (map #(str (name %) " = ?") (keys where-map)))
        params       (concat (vals set-map) (vals where-map))]
    (jdbc/execute!
     ds
     (into [(str "UPDATE " (name table) " SET " set-clause " WHERE " where-clause " RETURNING *")]
           params)
     default-opts)))

(defn delete!
  "Deletes rows matching where-clause and returns deleted rows.

   Example:
     (sql/delete! ds :users {:id some-id})"
  [ds table where-map]
  (let [where-clause (str/join " AND " (map #(str (name %) " = ?") (keys where-map)))
        params       (vals where-map)]
    (jdbc/execute!
     ds
     (into [(str "DELETE FROM " (name table) " WHERE " where-clause " RETURNING *")]
           params)
     default-opts)))

(defn find-by!
  "Finds rows where all conditions in where-map match.
   Returns a vector of rows.

   Example:
     (sql/find-by! ds :users {:email \"ana@borba.com\"})"
  [ds table where-map]
  (let [where-clause (str/join " AND " (map #(str (name %) " = ?") (keys where-map)))
        params       (vals where-map)]
    (jdbc/execute!
     ds
     (into [(str "SELECT * FROM " (name table) " WHERE " where-clause)]
           params)
     default-opts)))

(defn find-one-by!
  "Like find-by! but returns a single row (or nil).

   Example:
     (sql/find-one-by! ds :users {:id some-id})"
  [ds table where-map]
  (let [where-clause (str/join " AND " (map #(str (name %) " = ?") (keys where-map)))
        params       (vals where-map)]
    (jdbc/execute-one!
     ds
     (into [(str "SELECT * FROM " (name table) " WHERE " where-clause)]
           params)
     default-opts)))

(defmacro with-transaction
  "Executes body within a database transaction.
   Rolls back on any exception.

   Example:
     (sql/with-transaction [tx ds]
       (sql/insert! tx :users row)
       (sql/insert! tx :audit {:user-id (:id row)}))"
  [[binding ds] & body]
  `(jdbc/with-transaction [~binding ~ds]
     ~@body))
