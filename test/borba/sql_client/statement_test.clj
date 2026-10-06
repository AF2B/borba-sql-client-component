(ns borba.sql-client.statement-test
  (:require
   [borba.sql-client.statement :as statement]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :as tct]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]))

(defn- error-code
  [f]
  (try (f)
       nil
       (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))

(def ^:private invalid-identifier
  :borba.sql-client.statement/invalid-identifier)

(def ^:private empty-statement
  :borba.sql-client.statement/empty-statement)

(defn- joined
  "Joins the pieces of a statement, which are written apart to fit a line."
  [& pieces]
  (apply str pieces))

(deftest column-test
  (testing "writes a column in snake_case, quoted, from kebab or snake case"
    (is (= "\"id\"" (statement/column :id)))
    (is (= "\"created_at\"" (statement/column :created-at)))
    (is (= "\"created_at\"" (statement/column :created_at)))
    (is (= "\"created_at\"" (statement/column "created-at")))
    (is (= "\"_hidden\"" (statement/column :_hidden)))
    (is (= "\"c1\"" (statement/column :c1))))

  (testing "refuses a name that is not a lowercase snake_case name"
    (doseq [bad [:Name "Name" :1st "" :a.b :a-b$ "a b" "a\"b" "a;b" :user?
                 "id; DROP TABLE users" "x\" OR 1=1 --" "id) VALUES (1); --"]]
      (is (= invalid-identifier (error-code #(statement/column bad)))
          (pr-str bad))))

  (testing "refuses a namespaced keyword, which is a table's schema"
    (is (= invalid-identifier
           (error-code #(statement/column :users/id)))))

  (testing "refuses a name that PostgreSQL would cut"
    (is (= (str "\"" (apply str (repeat 63 "a")) "\"")
           (statement/column (apply str (repeat 63 "a")))))
    (is (= invalid-identifier
           (error-code #(statement/column (apply str (repeat 64 "a"))))))))

(deftest table-test
  (testing "writes a table in snake_case, quoted"
    (is (= "\"users\"" (statement/table :users)))
    (is (= "\"order_items\"" (statement/table :order-items)))
    (is (= "\"users\"" (statement/table "users"))))

  (testing "writes the schema of a namespaced keyword"
    (is (= "\"audit\".\"events\"" (statement/table :audit/events)))
    (is (= "\"billing_v2\".\"line_items\""
           (statement/table :billing-v2/line-items))))

  (testing "refuses a table or a schema that is not a name"
    (doseq [bad ["users; DROP" :Users :a/B :Audit/events "a.b"]]
      (is (= invalid-identifier (error-code #(statement/table bad)))
          (pr-str bad)))))

(deftest insert-statement-test
  (testing "writes the columns and the placeholders, and the values apart"
    (is (= [(joined "INSERT INTO \"users\" (\"id\", \"created_at\") "
                    "VALUES (?, ?) RETURNING *")
            1 :now]
           (statement/insert-statement :users
                                       (array-map :id 1 :created-at :now)))))

  (testing "writes a nil as a parameter, to be stored as NULL"
    (is (= ["INSERT INTO \"users\" (\"name\") VALUES (?) RETURNING *" nil]
           (statement/insert-statement :users {:name nil}))))

  (testing "keeps a value that looks like SQL as a value"
    (is (= ["INSERT INTO \"users\" (\"name\") VALUES (?) RETURNING *"
            "x'); DROP TABLE users; --"]
           (statement/insert-statement
            :users
            {:name "x'); DROP TABLE users; --"}))))

  (testing "refuses a column that is not a name, and an empty row"
    (is (= invalid-identifier
           (error-code #(statement/insert-statement
                         :users
                         {"name) VALUES (1); --" 1}))))
    (is (= empty-statement
           (error-code #(statement/insert-statement :users {}))))))

(deftest update-statement-test
  (testing "sets the values and matches the conditions, in that order"
    (is (= [(joined "UPDATE \"users\" SET \"status\" = ?, \"name\" = ? "
                    "WHERE \"id\" = ? RETURNING *")
            "active" "Ana" 7]
           (statement/update-statement :users
                                       (array-map :status "active" :name "Ana")
                                       {:id 7}))))

  (testing "matches a nil as IS NULL, which = NULL never is"
    (is (= [(joined "UPDATE \"users\" SET \"status\" = ? "
                    "WHERE \"id\" = ? AND \"deleted_at\" IS NULL RETURNING *")
            "x" 7]
           (statement/update-statement :users
                                       {:status "x"}
                                       (array-map :id 7 :deleted-at nil)))))

  (testing "refuses to set nothing, and to match every row"
    (is (= empty-statement
           (error-code #(statement/update-statement :users {} {:id 1}))))
    (is (= empty-statement
           (error-code #(statement/update-statement :users {:a 1} {}))))))

(deftest delete-and-select-statement-test
  (testing "deletes what matches"
    (is (= ["DELETE FROM \"users\" WHERE \"id\" = ? RETURNING *" 7]
           (statement/delete-statement :users {:id 7}))))

  (testing "selects what matches"
    (is (= [(joined "SELECT * FROM \"audit\".\"events\" "
                    "WHERE \"kind\" = ? AND \"seen_at\" IS NULL")
            "login"]
           (statement/select-statement :audit/events
                                       (array-map :kind "login"
                                                  :seen-at nil)))))

  (testing "refuses to match every row"
    (is (= empty-statement
           (error-code #(statement/delete-statement :users {}))))
    (is (= empty-statement
           (error-code #(statement/select-statement :users nil))))))

(def ^:private first-characters (seq "abcdefghijklmnopqrstuvwxyz_"))
(def ^:private other-characters (seq "abcdefghijklmnopqrstuvwxyz0123456789_-"))

(def ^:private valid-column-name
  (gen/fmap (fn [[head tail]] (str head tail))
            (gen/tuple (gen/elements first-characters)
                       (gen/fmap #(apply str %)
                                 (gen/vector (gen/elements other-characters)
                                             0
                                             20)))))

(tct/defspec a-column-is-never-anything-but-a-name
  (prop/for-all [text (gen/one-of [gen/string gen/string-ascii
                                   gen/string-alphanumeric])]
    (let [written (try (statement/column text)
                       (catch clojure.lang.ExceptionInfo _ nil))]
      (or (nil? written)
          (boolean (re-matches #"\"[a-z_][a-z0-9_]{0,62}\"" written))))))

(tct/defspec a-statement-has-a-parameter-for-each-placeholder
  (prop/for-all [columns (gen/not-empty (gen/set valid-column-name))
                 value   (gen/one-of [gen/small-integer
                                      gen/string
                                      (gen/return nil)])]
    (let [row                (zipmap columns (repeat value))
          [statement-text & parameters] (statement/insert-statement :t row)]
      (and (= (count columns) (count parameters))
           (= (count columns) (count (re-seq #"\?" statement-text)))
           (not (str/includes? statement-text "'"))))))
