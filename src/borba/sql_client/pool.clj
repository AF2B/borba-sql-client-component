(ns borba.sql-client.pool
  "The configuration of the connection pool: the options of
   `:components/database` checked, and turned into the properties of HikariCP.

   Every wait has a limit that is shorter than a request is willing to wait: a
   pool with no connection to give fails in five seconds, and not in the
   thirty that HikariCP waits unless told otherwise, and a statement the
   server has not finished in thirty seconds is cancelled by the server. A
   database that is down or slow is then an error that comes back, and not a
   thread that waits."
  (:require
   [clojure.string :as str]))

(def default-pool-name
  "The name of the pool in its logs and metrics, unless told otherwise."
  "borba-pool")

(def default-max-pool-size
  "The most connections the pool opens, unless told otherwise."
  10)

(def default-min-idle
  "The fewest idle connections the pool keeps, unless told otherwise."
  2)

(def default-connection-timeout-ms
  "How long a request waits for a connection, unless told otherwise."
  5000)

(def default-idle-timeout-ms
  "How long a connection can be idle before it is closed, unless told
   otherwise: ten minutes."
  600000)

(def default-max-lifetime-ms
  "How long a connection lives before it is replaced, unless told otherwise:
   thirty minutes."
  1800000)

(def default-statement-timeout-ms
  "How long the server lets a statement run before it cancels it, unless told
   otherwise."
  30000)

(def ^:private jdbc-url-prefix "jdbc:postgresql:")

(def ^:private address-in-url
  "The host and port, and the database, of a PostgreSQL JDBC URL, without the
   credentials that can come before the host or after the database."
  #"^jdbc:postgresql://(?:[^@/?]*@)?([^/?]+)(?:/([^?]*))?")

(defn- invalid-option
  [option
   value
   expected]
  (ex-info (str ":" (name option) " is " (if (= :password option)
                                           "not valid"
                                           (pr-str value))
                ", and must be " expected)
           {:error  ::invalid-option
            :option option}))

(defn- positive-int?
  [value]
  (and (int? value) (pos? value)))

(defn- non-negative-int?
  [value]
  (and (int? value) (not (neg? value))))

(defn redact
  "Returns where a JDBC URL points, as host:port/database, without the
   credentials, which can be in its user information or its query. This is
   what is safe to log.
   - jdbc-url: a PostgreSQL JDBC URL"
  [jdbc-url]
  (if-let [[_ host database] (re-find address-in-url jdbc-url)]
    (str host (when (seq database) (str "/" database)))
    "the database"))

(defn pool-config
  "Checks the options of the component and returns the properties of HikariCP
   that they make. Fails naming the first option that is not valid; the value
   of the password is never in the message.
   - jdbc-url: the PostgreSQL JDBC URL, which can carry the credentials
   - username: the user, when it is not in the URL
   - password: the password, when it is not in the URL
   - max-pool-size: the most connections (default 10)
   - min-idle: the fewest idle connections it keeps, zero up to the most
     (default 2)
   - connection-timeout-ms: how long a request waits for a connection, and
     how long the start waits for the first one, so that a database that
     cannot be reached fails the start (default 5000)
   - idle-timeout-ms: how long a connection can be idle before it is closed
     (default 600000)
   - max-lifetime-ms: how long a connection lives (default 1800000)
   - statement-timeout-ms: how long the server lets a statement run before it
     cancels it (default 30000)
   - pool-name: the name of the pool in its logs and metrics (default
     \"borba-pool\")"
  [{:keys [jdbc-url username password max-pool-size min-idle
           connection-timeout-ms idle-timeout-ms max-lifetime-ms
           statement-timeout-ms pool-name]
    :or   {max-pool-size         default-max-pool-size
           min-idle              default-min-idle
           connection-timeout-ms default-connection-timeout-ms
           idle-timeout-ms       default-idle-timeout-ms
           max-lifetime-ms       default-max-lifetime-ms
           statement-timeout-ms  default-statement-timeout-ms
           pool-name             default-pool-name}}]
  (when-not (and (string? jdbc-url) (str/starts-with? jdbc-url jdbc-url-prefix))
    (throw (invalid-option :jdbc-url jdbc-url
                           (str "a string that starts with "
                                jdbc-url-prefix))))
  (doseq [[option value] [[:max-pool-size max-pool-size]
                          [:connection-timeout-ms connection-timeout-ms]
                          [:idle-timeout-ms idle-timeout-ms]
                          [:max-lifetime-ms max-lifetime-ms]
                          [:statement-timeout-ms statement-timeout-ms]]]
    (when-not (positive-int? value)
      (throw (invalid-option option value "a positive integer"))))
  (when-not (and (non-negative-int? min-idle) (<= min-idle max-pool-size))
    (throw (invalid-option :min-idle min-idle
                           "an integer from zero to :max-pool-size")))
  (when-not (and (string? pool-name) (seq pool-name))
    (throw (invalid-option :pool-name pool-name "a non-empty string")))
  (when-not (or (nil? username) (string? username))
    (throw (invalid-option :username username "a string, or not given")))
  (when-not (or (nil? password) (string? password))
    (throw (invalid-option :password password "a string, or not given")))
  (cond-> {:jdbcUrl           jdbc-url
           :maximumPoolSize   max-pool-size
           :minimumIdle       min-idle
           :connectionTimeout connection-timeout-ms
           :initializationFailTimeout connection-timeout-ms
           :idleTimeout       idle-timeout-ms
           :maxLifetime       max-lifetime-ms
           :poolName          pool-name
           :connectionInitSql (str "SET statement_timeout = "
                                   statement-timeout-ms)}
    username (assoc :username username)
    password (assoc :password password)))
