(ns borba.sql-client.statement
  "SQL as data: the statements of the queries that read and write one table.

   A statement is a vector of the SQL text and its parameters, which is what
   next.jdbc takes:

     (insert-statement :users {:id 1 :created-at now})
     ;; => [\"INSERT INTO \\\"users\\\" (\\\"id\\\", \\\"created_at\\\")
     ;;      VALUES (?, ?) RETURNING *\"
     ;;     1 now]

   Names and values are kept apart. A value is always a parameter. A table or a
   column is written into the text, so it is held to what a PostgreSQL name
   written in snake_case is, quoted, and never taken from anywhere that is
   not the program: a name with anything else in it is refused, which is what
   keeps a column that came from a request from becoming SQL.

   A keyword or string is a name in kebab-case or snake_case, and either way it
   is the snake_case column: :created-at is created_at, as the columns read by
   the queries come back as :created-at."
  (:require
   [clojure.string :as str]))

(def ^:private valid-name #"[a-z_][a-z0-9_]*")
(def ^:private max-name-length 63)

(defn- invalid-identifier
  [identifier
   reason]
  (ex-info (str "not a valid name: " (pr-str identifier) ", " reason)
           {:error      ::invalid-identifier
            :identifier identifier}))

(defn- quoted
  "Returns a part of a name, as it is written in the SQL, or fails."
  [identifier
   part]
  (let [snake (str/replace part "-" "_")]
    (cond
      (not (re-matches valid-name snake))
      (throw (invalid-identifier
              identifier
              (str "use lowercase letters, digits and underscores, starting"
                   " with a letter or an underscore")))

      (> (count snake) max-name-length)
      (throw (invalid-identifier
              identifier
              (str "PostgreSQL cuts a name at " max-name-length
                   " characters")))

      :else
      (str "\"" snake "\""))))

(defn column
  "Returns a column name as it is written in the SQL: in snake_case, quoted.
   Fails when it is not a name that the queries can write.
   - column-name: a keyword without a namespace, or a string"
  [column-name]
  (when (and (keyword? column-name) (namespace column-name))
    (throw (invalid-identifier column-name "a column has no namespace")))
  (quoted column-name (name column-name)))

(defn table
  "Returns a table name as it is written in the SQL: in snake_case, quoted,
   with its schema when the keyword has a namespace, so :audit/events is
   \"audit\".\"events\". Fails when it is not a name that the queries can write.
   - table-name: a keyword, or a string"
  [table-name]
  (let [table-part (quoted table-name (name table-name))]
    (if-let [schema (when (keyword? table-name) (namespace table-name))]
      (str (quoted table-name schema) "." table-part)
      table-part)))

(defn- not-empty-map
  [what
   values]
  (when-not (seq values)
    (throw (ex-info (str "there are no " what)
                    {:error ::empty-statement :what what})))
  values)

(defn- conditions
  "Returns the WHERE conditions of a map, and their parameters. A nil value is
   IS NULL, because = NULL is never true. An empty map is refused, because it
   would be every row."
  [where-map]
  (let [entries (seq (not-empty-map "conditions, which would match every row"
                                    where-map))]
    [(str/join " AND "
               (map (fn [[column-name value]]
                      (str (column column-name)
                           (if (nil? value) " IS NULL" " = ?")))
                    entries))
     (into [] (comp (remove (comp nil? val)) (map val)) entries)]))

(defn insert-statement
  "Returns the statement that inserts a row and returns it.
   - table-name: the table, as for `table`
   - row: a map from the columns to their values"
  [table-name
   row]
  (let [entries (seq (not-empty-map "values to insert" row))]
    (into [(str "INSERT INTO " (table table-name)
                " (" (str/join ", " (map (comp column key) entries)) ")"
                " VALUES (" (str/join ", " (repeat (count entries) "?")) ")"
                " RETURNING *")]
          (map val entries))))

(defn update-statement
  "Returns the statement that updates the rows that match some conditions and
   returns them.
   - table-name: the table, as for `table`
   - set-map: a map from the columns to set to their new values
   - where-map: a map from the columns to the values the rows must have; a nil
     value is IS NULL, and the map must not be empty"
  [table-name
   set-map
   where-map]
  (let [entries                  (seq (not-empty-map "values to set" set-map))
        [where-clause params]    (conditions where-map)]
    (into [(str "UPDATE " (table table-name)
                " SET " (str/join ", "
                                  (map #(str (column (key %)) " = ?") entries))
                " WHERE " where-clause
                " RETURNING *")]
          (concat (map val entries) params))))

(defn delete-statement
  "Returns the statement that deletes the rows that match some conditions and
   returns them.
   - table-name: the table, as for `table`
   - where-map: a map from the columns to the values the rows must have; a nil
     value is IS NULL, and the map must not be empty"
  [table-name
   where-map]
  (let [[where-clause params] (conditions where-map)]
    (into [(str "DELETE FROM " (table table-name)
                " WHERE " where-clause
                " RETURNING *")]
          params)))

(defn select-statement
  "Returns the statement that selects the rows that match some conditions.
   - table-name: the table, as for `table`
   - where-map: a map from the columns to the values the rows must have; a nil
     value is IS NULL, and the map must not be empty"
  [table-name
   where-map]
  (let [[where-clause params] (conditions where-map)]
    (into [(str "SELECT * FROM " (table table-name)
                " WHERE " where-clause)]
          params)))
