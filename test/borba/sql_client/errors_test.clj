(ns borba.sql-client.errors-test
  (:require
   [borba.sql-client.errors :as errors]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.sql SQLException SQLTransientConnectionException)
   (org.postgresql.util PSQLException ServerErrorMessage)))

(set! *warn-on-reflection* true)

(def ^:private nul (str (char 0)))

(defn- server-error
  "A PostgreSQL exception as the driver builds it from what the server sent."
  [sql-state constraint table column]
  (PSQLException.
   (ServerErrorMessage.
    (str "SERROR" nul "VERROR" nul
         "C" sql-state nul
         "Mduplicate key value violates unique constraint" nul
         "DKey (email)=(ana@example.com) already exists." nul
         (when table (str "t" table nul))
         (when constraint (str "n" constraint nul))
         (when column (str "c" column nul))
         nul))))

(deftest sql-state-test
  (testing "names the errors a program can do something about"
    (doseq [[^String sql-state expected] errors/sql-state->error]
      (is (= {:error expected :sql-state sql-state}
             (errors/error-data (SQLException. "x" sql-state))))))

  (testing "names the class of the rest by its SQLSTATE"
    (is (= :connection-failure
           (:error (errors/error-data (SQLException. "x" "08006")))))
    (is (= :resource-exhausted
           (:error (errors/error-data (SQLException. "x" "53300")))))
    (is (= :unavailable
           (:error (errors/error-data (SQLException. "x" "57P01"))))))

  (testing "calls the rest a database error, with its SQLSTATE"
    (is (= {:error :database-error :sql-state "42601"}
           (errors/error-data (SQLException. "x" "42601"))))
    (is (= {:error :database-error}
           (errors/error-data (SQLException. "x")))))

  (testing "knows a pool that has no connection to give"
    (is (= {:error :connection-unavailable}
           (errors/error-data
            (SQLTransientConnectionException. "timed out"))))))

(deftest server-message-test
  (testing "has the names of the constraint, the table and the column"
    (is (= {:error      :unique-violation
            :sql-state  "23505"
            :constraint "users_email_key"
            :table      "users"
            :column     "email"}
           (errors/error-data
            (server-error "23505" "users_email_key" "users" "email")))))

  (testing "has only what the server names"
    (is (= {:error :unique-violation :sql-state "23505"}
           (errors/error-data (server-error "23505" nil nil nil)))))

  (testing "never has the message or the detail, which quote the row"
    (let [data (errors/error-data
                (server-error "23505" "users_email_key" "users" "email"))]
      (is (not (str/includes? (pr-str data) "ana@example.com")))
      (is (not (str/includes? (pr-str data) "duplicate key"))))))

(deftest cause-test
  (testing "finds the database exception in the causes"
    (is (= {:error :deadlock-detected :sql-state "40P01"}
           (errors/error-data
            (ex-info "failed" {} (RuntimeException.
                                  "wrapped"
                                  (SQLException. "x" "40P01")))))))

  (testing "is nil for what is not from the database"
    (is (nil? (errors/error-data (IllegalStateException. "no"))))
    (is (nil? (errors/error-data (ex-info "no" {}))))))
