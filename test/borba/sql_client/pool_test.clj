(ns borba.sql-client.pool-test
  (:require
   [borba.sql-client.pool :as pool]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private url "jdbc:postgresql://db.internal:5432/orders")

(defn- invalid-option
  "Returns the option that a configuration is refused for, or nil."
  [options]
  (try (pool/pool-config options)
       nil
       (catch clojure.lang.ExceptionInfo e
         (when (= :borba.sql-client.pool/invalid-option (:error (ex-data e)))
           (:option (ex-data e))))))

(deftest pool-config-test
  (testing "has limits that fail soon, and a statement timeout, by default"
    (is (= {:jdbcUrl                   url
            :maximumPoolSize           10
            :minimumIdle               2
            :connectionTimeout         5000
            :initializationFailTimeout 5000
            :idleTimeout               600000
            :maxLifetime       1800000
            :poolName          "borba-pool"
            :connectionInitSql "SET statement_timeout = 30000"}
           (pool/pool-config {:jdbc-url url}))))

  (testing "takes the credentials as they are given, when they are given"
    (is (= {:username "app" :password "secret"}
           (select-keys (pool/pool-config {:jdbc-url url
                                           :username "app"
                                           :password "secret"})
                        [:username :password]))))

  (testing "takes what it is told"
    (is (= {:maximumPoolSize   4
            :minimumIdle       0
            :connectionTimeout 1000
            :initializationFailTimeout 1000
            :idleTimeout       2000
            :maxLifetime       3000
            :poolName          "orders"
            :connectionInitSql "SET statement_timeout = 500"}
           (select-keys
            (pool/pool-config {:jdbc-url              url
                               :max-pool-size         4
                               :min-idle              0
                               :connection-timeout-ms 1000
                               :idle-timeout-ms       2000
                               :max-lifetime-ms       3000
                               :statement-timeout-ms  500
                               :pool-name             "orders"})
            [:maximumPoolSize :minimumIdle :connectionTimeout
             :initializationFailTimeout :idleTimeout :maxLifetime :poolName
             :connectionInitSql])))))

(deftest invalid-options-test
  (testing "the URL is a PostgreSQL JDBC URL"
    (doseq [bad [nil "" "postgres://db/x" "jdbc:mysql://db/x" 5432]]
      (is (= :jdbc-url (invalid-option {:jdbc-url bad})) (pr-str bad))))

  (testing "the sizes and the timeouts are positive integers"
    (doseq [option [:max-pool-size :connection-timeout-ms :idle-timeout-ms
                    :max-lifetime-ms :statement-timeout-ms]
            bad    [0 -1 1.5 "10"]]
      (is (= option (invalid-option {:jdbc-url url option bad}))
          (str option " " (pr-str bad)))))

  (testing "the idle connections are zero up to the size of the pool"
    (is (= :min-idle (invalid-option {:jdbc-url url :min-idle -1})))
    (is (= :min-idle (invalid-option {:jdbc-url url
                                      :max-pool-size 3
                                      :min-idle 4})))
    (is (nil? (invalid-option {:jdbc-url url :max-pool-size 3 :min-idle 3}))))

  (testing "the credentials and the name are strings"
    (is (= :username (invalid-option {:jdbc-url url :username 5})))
    (is (= :password (invalid-option {:jdbc-url url :password 5})))
    (is (= :pool-name (invalid-option {:jdbc-url url :pool-name ""})))))

(deftest password-is-not-in-a-message-test
  (testing "the message never has the password, whatever is wrong"
    (doseq [options [{:jdbc-url url :password :hunter2}
                     {:jdbc-url "nope" :password "hunter2"}]]
      (let [message (try (pool/pool-config options)
                         (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (not (str/includes? message "hunter2")))))))

(deftest redact-test
  (testing "keeps the host, the port and the database"
    (is (= "db.internal:5432/orders" (pool/redact url)))
    (is (= "db.internal/orders"
           (pool/redact "jdbc:postgresql://db.internal/orders"))))

  (testing "drops the user information and the query, which carry credentials"
    (is (= "db.internal:5432/orders"
           (pool/redact
            "jdbc:postgresql://app:hunter2@db.internal:5432/orders")))
    (is (= "db.internal:5432/orders"
           (pool/redact
            (str url "?user=app&password=hunter2&sslmode=require"))))
    (is (not (str/includes?
              (pool/redact "jdbc:postgresql://db/x?password=hunter2")
              "hunter2"))))

  (testing "keeps several hosts"
    (is (= "a:5432,b:5432/orders"
           (pool/redact "jdbc:postgresql://a:5432,b:5432/orders?x=y"))))

  (testing "has no database to show when there is none"
    (is (= "db.internal:5432"
           (pool/redact "jdbc:postgresql://db.internal:5432?password=x"))))

  (testing "says nothing it cannot read"
    (is (= "the database" (pool/redact "jdbc:postgresql:orders?password=x")))))
