# borba-sql-client-component

[![CI](https://github.com/AF2B/borba-sql-client-component/actions/workflows/ci.yml/badge.svg)](https://github.com/AF2B/borba-sql-client-component/actions/workflows/ci.yml)

PostgreSQL for a Borba service: a pool of connections ([HikariCP](https://github.com/brettwooldridge/HikariCP)) as an
[Integrant](https://github.com/weavejester/integrant) component, and the queries that read and write one table through
[next.jdbc](https://github.com/seancorfield/next-jdbc). Names and values are kept apart so that neither can inject the other, every
wait has a limit, and the failures of the database come back as data.

## Install

```clojure
io.github.af2b/borba-sql-client-component
{:git/url "https://github.com/AF2B/borba-sql-client-component"
 :git/tag "v2.0.0"
 :git/sha "<the commit of the tag, printed in the release notes>"}
```

It depends on Clojure, Integrant, `tools.logging`, next.jdbc, HikariCP and the PostgreSQL driver. It does not choose a logging
backend.

## Use

```clojure
{:service/namespaces [borba.sql-client]

 :ig/system
 {:components/database
  {:jdbc-url #env DATABASE_URL
   :username #env DATABASE_USER
   :password #env DATABASE_PASSWORD}}}
```

The value of the component is a HikariCP data source. Every function takes it first, or a connection inside a transaction:

```clojure
(require '[borba.sql-client :as sql])

(sql/insert! ds :users {:id (random-uuid) :name "Ana" :created-at (java.time.Instant/now)})
;; => {:id #uuid "41134ea2-…", :name "Ana", :email nil, :age nil, :created-at #inst "2026-10-06T20:55:10.641723Z", :deleted-at nil}

(sql/find-one-by! ds :users {:email "ana@example.com"})   ;; the row, or nil
(sql/find-by! ds :users {:name "Ana" :deleted-at nil})    ;; a vector of rows
(sql/update! ds :users {:name "Ana Silva"} {:id id})      ;; the updated rows
(sql/delete! ds :users {:id id})                          ;; the deleted rows

(sql/execute! ds ["SELECT * FROM users WHERE age > ?" 18])
(sql/execute-one! ds ["SELECT count(*) AS total FROM users"])
;; => {:total 41}
```

Rows come back as maps with unqualified keywords in kebab-case, so the column `created_at` is `:created-at`, and the columns of what
is written are named the same way. A `nil` in a condition is `IS NULL`, because `= NULL` is never true.

### Times

Times are `java.time`. An `Instant`, a `LocalDate` or a `LocalDateTime` can be written to a timestamp column, which the PostgreSQL
driver does not do by itself, and every timestamp column is read as an `Instant`. Use `timestamptz` for a point in time. The
reading is process-wide, as it is in next.jdbc, which is why starting the component is what turns it on.

| Option | What it is | Default |
|---|---|---|
| `:jdbc-url` | The PostgreSQL JDBC URL, which can carry the credentials | required |
| `:username`, `:password` | The credentials, when they are not in the URL | none |
| `:max-pool-size` | The most connections | `10` |
| `:min-idle` | The fewest idle connections, from zero up to the most | `2` |
| `:connection-timeout-ms` | How long a request waits for a connection, and the start for the first one | `5000` |
| `:idle-timeout-ms` | How long a connection can be idle before it is closed | `600000` |
| `:max-lifetime-ms` | How long a connection lives | `1800000` |
| `:statement-timeout-ms` | How long the server lets a statement run before it cancels it | `30000` |
| `:pool-name` | The name of the pool in its logs and metrics | `"borba-pool"` |

### Transactions

```clojure
(sql/transact! ds
               (fn [tx]
                 (let [user (sql/insert! tx :users row)]
                   (sql/insert! tx :audit/events {:kind "signup" :user-id (:id user)}))))
```

The function gets a connection that takes the place of the data source in the functions above. It commits when the function
returns, and rolls back and throws the exception again when it throws.

## Names are not SQL

A value is always a parameter. A table or a column is written into the SQL text, so the queries hold it to what a PostgreSQL name
in snake_case is, and quote it: lowercase letters, digits and underscores, starting with a letter or an underscore, up to 63
characters. A keyword or string in kebab-case or snake_case is the snake_case name. Anything else is refused before it is sent:

```clojure
(require '[borba.sql-client.statement :as statement])

(statement/insert-statement :users (array-map :id 1 :created-at :now))
;; => ["INSERT INTO \"users\" (\"id\", \"created_at\") VALUES (?, ?) RETURNING *" 1 :now]

(statement/select-statement :audit/events {:kind "login"})
;; => ["SELECT * FROM \"audit\".\"events\" WHERE \"kind\" = ?" "login"]

(statement/update-statement :users {:status "active"} (array-map :id 7 :deleted-at nil))
;; => ["UPDATE \"users\" SET \"status\" = ? WHERE \"id\" = ? AND \"deleted_at\" IS NULL RETURNING *" "active" 7]

(statement/column "name) VALUES (1); --")
;; throws ExceptionInfo "not a valid name: \"name) VALUES (1); --\", use lowercase letters, digits and underscores, ..."
;;   {:error :borba.sql-client.statement/invalid-identifier, :identifier "name) VALUES (1); --"}
```

That is what makes it safe to build a condition from the keys of a map that came from a request. The statements are data, so they
can be looked at before they run.

An empty set of conditions is refused too, because it would be every row: `delete!`, `update!`, `find-by!` and `find-one-by!`
fail with `::empty-statement`. A statement that is meant to touch every row is written with `execute!`, where it is visible.

A table in another schema is a namespaced keyword: `:audit/events` is `"audit"."events"`. A name that is not snake_case, such as a
table created with a quoted `"Users"`, is for `execute!`, with the SQL written out.

## Limits

Every wait has one that is shorter than a request is willing to wait:

- **A pool with no connection to give fails in five seconds**, and not in the thirty that HikariCP waits unless told otherwise.
  The failure is a `SQLTransientConnectionException`, and `error-data` names it `:connection-unavailable`.
- **A statement that runs for more than thirty seconds is cancelled by the server**, with `:query-canceled`, through
  `statement_timeout` on each connection, so a slow query or a lock does not hold a connection and a thread without end.
- **A database that cannot be reached fails the start.** HikariCP creates its pool at the first request; the component asks for a
  connection at the start, and a failure says where it tried and nothing else:

```clojure
(ig/init {:components/database {:jdbc-url "jdbc:postgresql://127.0.0.1:1/none?password=hunter2"}})
;; throws the ExceptionInfo of Integrant, whose cause (ex-cause) is
;;   "the database pool cannot start on 127.0.0.1:1/none"
;;   {:error :borba.sql-client/cannot-connect, :address "127.0.0.1:1/none"}
```

The credentials of a JDBC URL, in its user information or in its query, are never logged or put in a message:

```clojure
(borba.sql-client.pool/redact "jdbc:postgresql://app:hunter2@db.internal:5432/orders?sslmode=require")
;; => "db.internal:5432/orders"
```

`ready?` is for a readiness check. It is true when the database answers a query within two seconds, and false when it does not or
when the pool is closed. It never throws.

## Failures as data

A failure of the database is the exception next.jdbc throws. `error-data` turns it into the convention of `borba.railway`: a map
with an `:error` keyword to match on, and the names that explain it.

```clojure
(try (sql/insert! ds :users {:id (random-uuid) :name "Ana" :email "ana@example.com"})
     (catch java.sql.SQLException e
       (sql/error-data e)))
;; => {:error      :unique-violation
;;     :sql-state  "23505"
;;     :constraint "users_email_key"
;;     :table      "users"}
```

| `:error` | SQLSTATE | When |
|---|---|---|
| `:unique-violation` | 23505 | A value that has to be unique is repeated |
| `:foreign-key-violation` | 23503 | A reference to a row that is not there |
| `:not-null-violation` | 23502 | A value is missing (`:column` says which) |
| `:check-violation` | 23514 | A value the check refuses |
| `:exclusion-violation` | 23P01 | Two rows the exclusion constraint keeps apart |
| `:serialization-failure`, `:deadlock-detected` | 40001, 40P01 | A transaction that has to be tried again |
| `:lock-not-available`, `:query-canceled` | 55P03, 57014 | A lock that was not given, a statement that was cancelled |
| `:invalid-text-representation`, `:numeric-value-out-of-range`, `:string-too-long` | 22P02, 22003, 22001 | A value the column cannot hold |
| `:connection-failure`, `:resource-exhausted`, `:unavailable` | class 08, 53, 57 | The database cannot be reached, is out of resources, or is shutting down |
| `:connection-unavailable` | none | The pool has no connection to give |
| `:database-error` | any other | Anything else, with its `:sql-state` |

The map has the names of the constraint, the table and the column and never the message or the detail of the server, which quote
the values of the row: the email that was repeated, the document that was refused. `error-data` is nil for an exception that is not
the database's, so a caller can rethrow it.

## API

| Name | What it does |
|---|---|
| `execute!`, `execute-one!` | Run a statement of your own, `["SQL" param ...]` |
| `insert!`, `update!`, `delete!` | Write one table, and return the rows |
| `find-by!`, `find-one-by!` | Read one table by the values of a map |
| `transact!` | Run a function in a transaction |
| `ready?` | Whether the database answers |
| `error-data` | The failure of a database exception, as data |
| `borba.sql-client.statement` | The statements, as data, and the names they write |
| `borba.sql-client.pool` | The checked configuration of the pool, and `redact` |
| `borba.sql-client.errors` | The classification of the failures |
| `:components/database` | The Integrant key that starts and stops the pool |

## Tests

The unit suite runs anywhere. The integration suite runs against a real PostgreSQL, which the pipeline provides, and which you can
start with Docker:

```bash
docker run --rm -d --name borba-sql-it -p 127.0.0.1:55432:5432 \
  -e POSTGRES_USER=ci -e POSTGRES_PASSWORD=ci -e POSTGRES_DB=ci postgres:18.6-alpine

DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/ci DATABASE_USER=ci DATABASE_PASSWORD=ci \
  make test-integration
```

It covers the queries, the names that are not SQL, the transactions, the real failures (a repeated unique value, a missing value, a
check, a reference), the statement timeout, a pool with no connection to give, and a database that cannot be reached.

## Design notes

- **The injection that is left is the one in the SQL you write.** `execute!` takes the SQL as you wrote it and the values apart,
  and the builders take names from the program. Neither joins a value into the text.
- **A failure is data until a caller decides it is not.** The domain cares that an email is taken, not that the driver threw: the
  `:error` keyword is what a handler matches on, and what `borba.railway/failure` and the HTTP layer already speak.
- **The pool is checked at the start, not at the first request.** An outage that is already there when the service starts is a
  start that fails, with the address in it, and not a service that is up and answers every request with a 500.

## Development

```bash
make check      # lint, format, conventions, reflection, tests, coverage
make ci         # everything the pipelines enforce
```

See [CONTRIBUTING.md](CONTRIBUTING.md). The repository follows the [Borba standard](https://github.com/AF2B/borba-tooling/blob/main/docs/standard.md).

## License

[MIT](LICENSE)
