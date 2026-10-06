(ns borba.sql-client
  "PostgreSQL for a service: a pool of connections as an Integrant component,
   and the queries that read and write one table.

     :components/database
     {:jdbc-url #env DATABASE_URL
      :username #env DATABASE_USER
      :password #env DATABASE_PASSWORD}

   The value is a HikariCP data source, which every function here takes first.
   Rows come back as maps with unqualified keywords in kebab-case, so the
   column created_at is :created-at, and the columns of what is written are
   written the same way. Values are always parameters; the names of tables and
   columns are held to what a snake_case name is (see
   borba.sql-client.statement), so a name cannot be made into SQL.

   Times are java.time: an Instant, a LocalDate or a LocalDateTime can be
   written to a timestamp column, which the PostgreSQL driver does not do by
   itself, and starting the component makes every timestamp column be read as
   an Instant. That is process-wide, as next.jdbc makes it, which is why it is
   the component's start that does it and not the loading of this namespace.

   A failure of the database is an exception, as next.jdbc throws it, and
   `error-data` turns it into data to match on."
  (:require
   [borba.sql-client.errors :as errors]
   [borba.sql-client.pool :as pool]
   [borba.sql-client.statement :as statement]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [next.jdbc :as jdbc]
   [next.jdbc.connection :as connection]
   [next.jdbc.date-time :as date-time]
   [next.jdbc.result-set :as rs])
  (:import
   (com.zaxxer.hikari HikariDataSource)))

(set! *warn-on-reflection* true)

(def ^:private default-opts
  {:builder-fn rs/as-unqualified-kebab-maps})

(def ^:private ready-timeout-seconds 2)

;; The component

(defn- start-pool
  "Creates the pool and asks it for a connection, which HikariCP does not do
   until the first request, so that a database that cannot be reached fails the
   start and not the first request. Closes what it opened when it fails."
  [config
   address]
  (let [^HikariDataSource datasource (connection/->pool HikariDataSource
                                                        config)]
    (try
      (with-open [_connection (jdbc/get-connection datasource)]
        datasource)
      (catch Exception cause
        (.close datasource)
        (throw (ex-info (str "the database pool cannot start on " address)
                        {:error   ::cannot-connect
                         :address address}
                        cause))))))

(defmethod ig/init-key :components/database
  [_ options]
  (let [config  (pool/pool-config options)
        address (pool/redact (:jdbcUrl config))
        _       (date-time/read-as-instant)
        started (start-pool config address)]
    (log/infof "database pool %s started on %s (up to %d connections)"
               (:poolName config)
               address
               (:maximumPoolSize config))
    started))

(defmethod ig/halt-key! :components/database
  [_ datasource]
  (when (instance? HikariDataSource datasource)
    (.close ^HikariDataSource datasource)
    (log/infof "database pool %s stopped"
               (.getPoolName ^HikariDataSource datasource))))

;; Queries

(defn execute!
  "Executes a statement and returns the rows, as a vector of maps.
   - ds: the data source of the component, or a connection in a transaction
   - sql: a vector of the SQL text and its parameters
   - opts: next.jdbc options, merged over the ones that give kebab-case keys
     (optional)"
  ([ds sql]
   (execute! ds sql {}))
  ([ds
    sql
    opts]
   (jdbc/execute! ds sql (merge default-opts opts))))

(defn execute-one!
  "Executes a statement and returns the first row, or nil.
   - ds: the data source of the component, or a connection in a transaction
   - sql: a vector of the SQL text and its parameters
   - opts: next.jdbc options, merged over the ones that give kebab-case keys
     (optional)"
  ([ds sql]
   (execute-one! ds sql {}))
  ([ds
    sql
    opts]
   (jdbc/execute-one! ds sql (merge default-opts opts))))

(defn insert!
  "Inserts a row and returns it, as the database stored it.
   - ds: the data source of the component, or a connection in a transaction
   - table-name: the table, a keyword such as :users or :audit/events
   - row: a map from the columns to their values"
  [ds
   table-name
   row]
  (execute-one! ds (statement/insert-statement table-name row)))

(defn update!
  "Updates the rows that have the values of a map and returns them.
   - ds: the data source of the component, or a connection in a transaction
   - table-name: the table, a keyword such as :users or :audit/events
   - set-map: a map from the columns to set to their new values
   - where-map: a map from the columns to the values the rows must have; a nil
     value is IS NULL, and the map must not be empty"
  [ds
   table-name
   set-map
   where-map]
  (execute! ds (statement/update-statement table-name set-map where-map)))

(defn delete!
  "Deletes the rows that have the values of a map and returns them.
   - ds: the data source of the component, or a connection in a transaction
   - table-name: the table, a keyword such as :users or :audit/events
   - where-map: a map from the columns to the values the rows must have; a nil
     value is IS NULL, and the map must not be empty"
  [ds
   table-name
   where-map]
  (execute! ds (statement/delete-statement table-name where-map)))

(defn find-by!
  "Returns the rows that have the values of a map, as a vector.
   - ds: the data source of the component, or a connection in a transaction
   - table-name: the table, a keyword such as :users or :audit/events
   - where-map: a map from the columns to the values the rows must have; a nil
     value is IS NULL, and the map must not be empty"
  [ds
   table-name
   where-map]
  (execute! ds (statement/select-statement table-name where-map)))

(defn find-one-by!
  "Returns the first row that has the values of a map, or nil.
   - ds: the data source of the component, or a connection in a transaction
   - table-name: the table, a keyword such as :users or :audit/events
   - where-map: a map from the columns to the values the rows must have; a nil
     value is IS NULL, and the map must not be empty"
  [ds
   table-name
   where-map]
  (execute-one! ds (statement/select-statement table-name where-map)))

;; Transactions

(defn transact!
  "Calls a function with a connection in a transaction, and commits when it
   returns. The transaction is rolled back, and the exception thrown again,
   when the function throws.
   - ds: the data source of the component
   - f: a function of the connection, which takes the place of the data source
     in the functions of this namespace"
  [ds f]
  (jdbc/transact ds f))

;; Health and failures

(defn ready?
  "Returns true when the database answers a query within two seconds, and false
   when it does not or when there is no connection to ask it with. It never
   throws, so a readiness check can call it.
   - ds: the data source of the component"
  [ds]
  (try
    (= 1 (:one (execute-one! ds
                             ["SELECT 1 AS one"]
                             {:timeout ready-timeout-seconds})))
    (catch Exception _
      false)))

(defn error-data
  "Returns the failure of a database exception as data, with an :error keyword
   such as :unique-violation and the names of the constraint and the table, or
   nil when the exception is not from the database. See
   borba.sql-client.errors.
   - e: the exception"
  [e]
  (errors/error-data e))
