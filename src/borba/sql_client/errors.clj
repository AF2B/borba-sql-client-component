(ns borba.sql-client.errors
  "The failures of the database as data, in the convention of borba.railway: a
   map with an :error keyword that a caller matches on, and what explains it.

     (try (sql/insert! ds :users row)
          (catch java.sql.SQLException e
            (errors/error-data e)))
     ;; => {:error :unique-violation
     ;;     :sql-state \"23505\"
     ;;     :constraint \"users_email_key\"
     ;;     :table \"users\"}

   The map has the names of the constraint, the table and the column, which say
   what went wrong, and never the message or the detail of the server, which
   quote the values of the row: an email, a document, a name."
  (:import
   (java.sql SQLException SQLTransientConnectionException)
   (org.postgresql.util PSQLException)))

(set! *warn-on-reflection* true)

(def sql-state->error
  "The error of each SQLSTATE that the database answers with and that a
   program can do something about."
  {"23505" :unique-violation
   "23503" :foreign-key-violation
   "23502" :not-null-violation
   "23514" :check-violation
   "23P01" :exclusion-violation
   "22P02" :invalid-text-representation
   "22003" :numeric-value-out-of-range
   "22001" :string-too-long
   "40001" :serialization-failure
   "40P01" :deadlock-detected
   "55P03" :lock-not-available
   "57014" :query-canceled})

(def ^:private sql-state-class->error
  {"08" :connection-failure
   "53" :resource-exhausted
   "57" :unavailable})

(def ^:private sql-state-class-length 2)

(defn- sql-exception
  "Returns the SQLException of an exception, or the first one in its causes."
  ^SQLException [^Throwable e]
  (loop [current e]
    (cond
      (instance? SQLException current) current
      (some? (ex-cause current))       (recur (ex-cause current))
      :else                            nil)))

(defn- error-code
  "Returns the keyword of an error from its SQLSTATE, or from the class of the
   exception when it has none, as when the pool has no connection to give."
  [^SQLException e]
  (let [sql-state (.getSQLState e)]
    (cond
      (and sql-state (contains? sql-state->error sql-state))
      (sql-state->error sql-state)

      (and sql-state (>= (count sql-state) sql-state-class-length))
      (get sql-state-class->error
           (subs sql-state 0 sql-state-class-length)
           :database-error)

      (instance? SQLTransientConnectionException e)
      :connection-unavailable

      :else
      :database-error)))

(defn error-data
  "Returns the failure of a database exception as data, or nil when the
   exception, and its causes, are not from the database. The map has the :error
   keyword, the :sql-state when there is one and, when the server names them,
   the :constraint, the :table and the :column.
   - e: the exception"
  [e]
  (when-let [sql-ex (sql-exception e)]
    (let [server-message (when (instance? PSQLException sql-ex)
                           (.getServerErrorMessage ^PSQLException sql-ex))]
      (cond-> {:error (error-code sql-ex)}
        (.getSQLState sql-ex)
        (assoc :sql-state (.getSQLState sql-ex))

        (and server-message (.getConstraint server-message))
        (assoc :constraint (.getConstraint server-message))

        (and server-message (.getTable server-message))
        (assoc :table (.getTable server-message))

        (and server-message (.getColumn server-message))
        (assoc :column (.getColumn server-message))))))
