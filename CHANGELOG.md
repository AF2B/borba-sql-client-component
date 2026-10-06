# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.0.0] - 2026-10-06

### Added

- `borba.sql-client.statement`, the statements of the queries as data. A table or a column is held to a snake_case name and
  quoted, a kebab-case keyword is the snake_case column, and a name with anything else in it is refused. Values are always
  parameters.
- A `nil` in a condition is `IS NULL`. An empty set of conditions, which would be every row, and an empty row to insert are
  refused.
- `error-data`, and `borba.sql-client.errors`, which turn a database exception into data: an `:error` keyword for the
  SQLSTATEs a program can act on (`:unique-violation`, `:foreign-key-violation`, `:not-null-violation`, `:check-violation`,
  `:serialization-failure`, `:deadlock-detected`, `:query-canceled` and more), the class of the rest, and the names of the
  constraint, the table and the column, never the message or the detail, which quote the row.
- `ready?`, for a readiness check: true when the database answers a query within two seconds, and false, without throwing, when it
  does not or the pool is closed.
- `transact!`, which runs a function in a transaction.
- `:statement-timeout-ms`, `:connection-timeout-ms`, `:idle-timeout-ms`, `:max-lifetime-ms` and `:pool-name`. The options are
  checked when the system starts, and a failure names the option and never the password.
- The pool asks for a connection at the start, and a database that cannot be reached fails the start with `::cannot-connect`
  and where it tried. HikariCP created the pool at the first request.
- A test suite with an integration suite against a real PostgreSQL, run by the pipeline, and unit tests with generated names that
  check that a column is never anything but a name.

### Changed

- **Breaking:** `insert!`, `update!`, `delete!`, `find-by!` and `find-one-by!` no longer write the name of a table or a column as it
  is. A name that is not snake_case is refused, and a kebab-case key is the snake_case column, which it was not.
- **Breaking:** `update!`, `delete!`, `find-by!` and `find-one-by!` refuse an empty map of conditions, which meant every row, and a
  `nil` value is `IS NULL`, where it was `= NULL`, which matches nothing.
- **Breaking:** a request waits five seconds for a connection, where it waited thirty, and a statement is cancelled by the server
  after thirty.
- **Breaking:** `with-transaction` is gone. `transact!` takes a function.
- **Breaking:** timestamp columns are read as `java.time.Instant`, where they were `java.sql.Timestamp`, and an `Instant`, a
  `LocalDate` or a `LocalDateTime` can be written. The PostgreSQL driver refuses an `Instant` by itself.
- **Breaking:** the component logs through `tools.logging` and no longer prints the JDBC URL, which can carry the credentials: it
  logs the host, the port and the database. It no longer depends on a logging backend.
- Moves to Integrant 1.0, next.jdbc 1.3.1118, HikariCP 7.1.0 and the PostgreSQL driver 42.7.13.
- The published library is named `io.github.af2b/borba-sql-client-component`.

## [1.0.0] - 2026-03-29

First release: the `:components/database` Integrant component, which starts a HikariCP pool, and `execute!`, `execute-one!`,
`insert!`, `update!`, `delete!`, `find-by!`, `find-one-by!` and `with-transaction`.

[Unreleased]: https://github.com/AF2B/borba-sql-client-component/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/AF2B/borba-sql-client-component/compare/v1.0.0...v2.0.0
[1.0.0]: https://github.com/AF2B/borba-sql-client-component/releases/tag/v1.0.0
