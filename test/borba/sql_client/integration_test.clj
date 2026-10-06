(ns ^:integration borba.sql-client.integration-test
  "Runs against a real PostgreSQL, which the pipeline provides and which a
   developer starts with

     docker run --rm -d --name borba-sql-it -p 127.0.0.1:55432:5432 \\
       -e POSTGRES_USER=ci -e POSTGRES_PASSWORD=ci -e POSTGRES_DB=ci \\
       postgres:18.6-alpine

   and points the tests at with DATABASE_URL, DATABASE_USER and
   DATABASE_PASSWORD (jdbc:postgresql://127.0.0.1:55432/ci, ci and ci)."
  (:require
   [borba.sql-client :as sql]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [integrant.core :as ig])
  (:import
   (java.sql SQLTransientConnectionException)
   (java.time Instant LocalDate LocalDateTime)
   (java.time.temporal ChronoUnit)
   (java.util.concurrent CountDownLatch TimeUnit)))

(set! *warn-on-reflection* true)

(def ^:private latch-wait-seconds 10)

(defn- environment
  [variable]
  (or (System/getenv variable)
      (throw (ex-info (str "set " variable " to run the integration tests")
                      {:variable variable}))))

(defn- options
  "The options of a pool to the database of the tests, with more options."
  [more]
  (merge {:jdbc-url (environment "DATABASE_URL")
          :username (environment "DATABASE_USER")
          :password (environment "DATABASE_PASSWORD")}
         more))

(def ^:private suffix (subs (str (random-uuid)) 0 8))
(def ^:private users (keyword (str "it_users_" (str/replace suffix "-" ""))))
(def ^:private orders (keyword (str "it_orders_" (str/replace suffix "-" ""))))

(def ^:dynamic *ds*
  "The data source of the tests, bound for the run of the namespace."
  nil)

(defn- with-database
  "Starts a pool and makes the tables of the tests, and drops them after."
  [run-tests]
  (let [system (ig/init {:components/database (options {})})
        ds     (:components/database system)]
    (try
      (sql/execute! ds [(str "CREATE TABLE " (name users) " ("
                             "id uuid PRIMARY KEY, "
                             "name text NOT NULL, "
                             "email text UNIQUE, "
                             "age integer CHECK (age >= 0), "
                             "created_at timestamptz NOT NULL DEFAULT now(), "
                             "deleted_at timestamptz)")])
      (sql/execute! ds [(str "CREATE TABLE " (name orders) " ("
                             "id serial PRIMARY KEY, "
                             "user_id uuid NOT NULL REFERENCES "
                             (name users) "(id))")])
      (binding [*ds* ds]
        (run-tests))
      (finally
        (sql/execute! ds [(str "DROP TABLE IF EXISTS " (name orders))])
        (sql/execute! ds [(str "DROP TABLE IF EXISTS " (name users))])
        (ig/halt! system)))))

(use-fixtures :once with-database)

(defn- new-user
  [more]
  (merge {:id (random-uuid)
          :name "Ana"
          :email (str (random-uuid) "@example.com")}
         more))

(defn- failure
  "Returns the data of the database failure a function throws, or nil."
  [f]
  (try (f)
       nil
       (catch Exception e (sql/error-data e))))

(deftest queries-test
  (testing "inserts a row and returns it with kebab-case keys"
    (let [user (new-user {:age 30})
          row  (sql/insert! *ds* users user)]
      (is (= (select-keys user [:id :name :email :age])
             (select-keys row [:id :name :email :age])))
      (is (some? (:created-at row)))
      (is (contains? row :deleted-at))))

  (testing "finds by the values of a map, one or many"
    (let [user (new-user {:name "Bia"})
          _    (sql/insert! *ds* users user)]
      (is (= (:id user)
             (:id (sql/find-one-by! *ds* users {:email (:email user)}))))
      (is (= [(:id user)]
             (mapv :id (sql/find-by! *ds* users {:email (:email user)}))))
      (is (nil? (sql/find-one-by! *ds* users {:email "nobody@example.com"})))
      (is (= [] (sql/find-by! *ds* users {:email "nobody@example.com"})))))

  (testing "writes and reads times as java.time"
    (let [moment (.truncatedTo (Instant/now) ChronoUnit/MICROS)
          user   (new-user {:created-at moment
                            :deleted-at (LocalDateTime/of 2026 10 6 12 0)})
          row    (sql/insert! *ds* users user)]
      (is (= moment (:created-at row)))
      (is (instance? Instant (:deleted-at row)))
      (is (= [(:id user)]
             (mapv :id (sql/find-by! *ds* users {:created-at moment}))))
      (is (some? (sql/update! *ds* users
                              {:deleted-at (LocalDate/of 2026 10 7)}
                              {:id (:id user)})))))

  (testing "finds a nil as IS NULL"
    (let [user (new-user {:name "NullAge" :age nil})
          _    (sql/insert! *ds* users user)
          rows (sql/find-by! *ds* users {:name "NullAge" :age nil})]
      (is (= [(:id user)] (mapv :id rows)))))

  (testing "updates the rows that match, and returns them"
    (let [user (new-user {:name "Caio"})
          _    (sql/insert! *ds* users user)
          rows (sql/update! *ds* users {:name "Caio Silva" :age 41}
                            {:id (:id user)})]
      (is (= [{:id (:id user) :name "Caio Silva" :age 41}]
             (mapv #(select-keys % [:id :name :age]) rows)))))

  (testing "sets a column to null"
    (let [user (new-user {:age 9})
          _    (sql/insert! *ds* users user)
          rows (sql/update! *ds* users {:age nil} {:id (:id user)})]
      (is (nil? (:age (first rows))))))

  (testing "deletes the rows that match, and returns them"
    (let [user (new-user {:name "Duda"})
          _    (sql/insert! *ds* users user)
          rows (sql/delete! *ds* users {:id (:id user)})]
      (is (= [(:id user)] (mapv :id rows)))
      (is (nil? (sql/find-one-by! *ds* users {:id (:id user)})))))

  (testing "runs a statement of its own"
    (is (= {:total 2}
           (sql/execute-one! *ds* ["SELECT 1 + 1 AS total"])))
    (is (= [{:n 1} {:n 2}]
           (sql/execute! *ds* ["SELECT n FROM generate_series(1, 2) AS n"])))))

(deftest names-are-not-sql-test
  (testing "a column that is SQL is refused before anything is sent"
    (is (= :borba.sql-client.statement/invalid-identifier
           (try (sql/find-by! *ds* users {"name = name OR 1=1; --" "x"})
                (catch clojure.lang.ExceptionInfo e (:error (ex-data e))))))
    (is (= :borba.sql-client.statement/invalid-identifier
           (try (sql/find-by! *ds* "users; DROP TABLE users" {:id 1})
                (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))))

  (testing "a value that is SQL is only a value"
    (let [attack "x'; DELETE FROM users; --"
          user   (new-user {:name attack})
          row    (sql/insert! *ds* users user)]
      (is (= attack (:name row)))
      (is (= attack (:name (sql/find-one-by! *ds* users {:id (:id user)})))))))

(deftest transactions-test
  (testing "commits what the function does when it returns"
    (let [user (new-user {:name "Eva"})]
      (sql/transact! *ds* (fn [tx] (sql/insert! tx users user)))
      (is (some? (sql/find-one-by! *ds* users {:id (:id user)})))))

  (testing "rolls back, and throws again, when the function throws"
    (let [user (new-user {:name "Fred"})]
      (is (thrown? IllegalStateException
                   (sql/transact! *ds*
                                  (fn [tx]
                                    (sql/insert! tx users user)
                                    (throw (IllegalStateException. "no"))))))
      (is (nil? (sql/find-one-by! *ds* users {:id (:id user)}))))))

(deftest failures-test
  (testing "a repeated unique value is a unique violation, with its names"
    (let [user (new-user {})
          _    (sql/insert! *ds* users user)
          data (failure #(sql/insert! *ds* users
                                      (new-user {:email (:email user)})))]
      (is (= :unique-violation (:error data)))
      (is (= "23505" (:sql-state data)))
      (is (= (str (name users) "_email_key") (:constraint data)))
      (is (= (name users) (:table data)))
      (is (not (str/includes? (pr-str data) (:email user))))))

  (testing "a missing value is a not null violation, with its column"
    (is (= {:error :not-null-violation :column "name"}
           (select-keys (failure #(sql/execute!
                                   *ds*
                                   [(str "INSERT INTO " (name users)
                                         " (id) VALUES (?)")
                                    (random-uuid)]))
                        [:error :column]))))

  (testing "a value the check refuses is a check violation"
    (is (= :check-violation
           (:error (failure #(sql/insert! *ds* users
                                          (new-user {:age -1})))))))

  (testing "a reference to nothing is a foreign key violation"
    (is (= :foreign-key-violation
           (:error (failure #(sql/insert! *ds* orders
                                          {:user-id (random-uuid)}))))))

  (testing "a query the database cannot make sense of is a database error"
    (is (= :database-error
           (:error (failure #(sql/find-one-by! *ds* users {:id "no"}))))))

  (testing "an exception that is not the database's is nil"
    (is (nil? (sql/error-data (IllegalStateException. "no"))))))

(deftest health-test
  (testing "the database is ready"
    (is (true? (sql/ready? *ds*)))))

(deftest timeouts-test
  (testing "the server cancels a statement that takes longer than the limit"
    (let [system  (ig/init {:components/database
                            (options {:statement-timeout-ms 500})})
          ds      (:components/database system)
          started (System/nanoTime)]
      (try
        (is (= :query-canceled
               (:error (failure #(sql/execute! ds ["SELECT pg_sleep(5)"])))))
        (is (< (/ (- (System/nanoTime) started) 1e6) 3000))
        (finally
          (ig/halt! system)))))

  (testing "the statement timeout is the one the pool was given"
    (let [system (ig/init {:components/database
                           (options {:statement-timeout-ms 1500})})]
      (try
        (is (= {:statement-timeout "1500ms"}
               (sql/execute-one! (:components/database system)
                                 ["SHOW statement_timeout"])))
        (finally
          (ig/halt! system)))))

  (testing "a pool with no connection to give fails soon, and says so"
    (let [system  (ig/init {:components/database
                            (options {:max-pool-size         1
                                      :min-idle              0
                                      :connection-timeout-ms 400})})
          ds      (:components/database system)
          holding (CountDownLatch. 1)
          release (CountDownLatch. 1)
          holder  (future
                    (sql/transact! ds
                                   (fn [_tx]
                                     (.countDown holding)
                                     (.await release
                                             latch-wait-seconds
                                             TimeUnit/SECONDS))))]
      (try
        (is (.await holding latch-wait-seconds TimeUnit/SECONDS))
        (let [started (System/nanoTime)
              thrown  (try (sql/execute! ds ["SELECT 1"])
                           nil
                           (catch SQLTransientConnectionException e e))]
          (is (some? thrown))
          (is (= :connection-unavailable (:error (sql/error-data thrown))))
          (is (< (/ (- (System/nanoTime) started) 1e6) 3000)))
        (finally
          (.countDown release)
          @holder
          (ig/halt! system))))))

(deftest lifecycle-test
  (testing "is not ready once the pool is closed"
    (let [system (ig/init {:components/database (options {})})
          ds     (:components/database system)]
      (is (true? (sql/ready? ds)))
      (ig/halt! system)
      (is (false? (sql/ready? ds)))))

  (testing "a database that cannot be reached fails the start naming only
            where it was"
    (let [thrown (try (ig/init
                       {:components/database
                        {:jdbc-url (str "jdbc:postgresql://127.0.0.1:1/none"
                                        "?password=hunter2")
                         :password "hunter2"
                         :connection-timeout-ms 500}})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))
          cause  (ex-cause thrown)]
      (is (= {:error   :borba.sql-client/cannot-connect
              :address "127.0.0.1:1/none"}
             (ex-data cause)))
      (is (not (str/includes? (ex-message cause) "hunter2"))))))
