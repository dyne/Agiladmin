# Agiladmin

Agiladmin is a Clojure web application for payroll, timesheet, and
project administration. It imports monthly `.xlsx` timesheets, loads
project definitions from YAML files in a Git-backed budgets
repository, computes hours and costs, and renders HTML reports for
personnel and projects.

The current codebase targets `org.clojure/clojure` `1.12.4` and starts
with the Clojure CLI. Authentication is backend-driven: PocketBase is
supported for real deployments, and a development-only fallback
backend is available for local manual testing.

## Current State

- Runtime: Ring + Compojure on Jetty
- Data processing: Incanter datasets, Docjure / Apache POI, YAML files
- Storage model:
  - project metadata and uploaded spreadsheets live in a Git-managed budgets directory
  - authentication is handled through a backend abstraction, with PocketBase currently implemented
- Build: Clojure CLI with `deps.edn`
- Tests: Midje

The app is old and fairly stateful. Startup performs real side effects:

- configuration is loaded from YAML
- an SSH keypair is generated if the configured private key does not exist
- the authentication backend is initialized and health-checked

## Requirements

- Java and the Clojure CLI
- a writable budgets Git checkout or clone target
- a valid `agiladmin.yaml` configuration file, or an explicit config path via `AGILADMIN_CONF`
- for real auth flows: a reachable PocketBase instance

## Running

The main entry point is:

```sh
clj -M:run
```

That runs `agiladmin.main`, which initializes the app and starts Jetty with the host and port from config.

### Local Development With Dev Auth

For manual testing without PocketBase:

```sh
make run
```

This sets `AGILADMIN_DEV_AUTH=1` and enables a simple in-memory development auth backend.

Use these credentials:

- username: `admin`
- password: `admin`

The dev auth backend only supports sign-in. Sign-up and pending-user flows are intentionally disabled there.

### Running With PocketBase

The repository ships an example config at [doc/agiladmin.pocketbase.yaml](/home/jrml/devel/planb-agiladmin/doc/agiladmin.pocketbase.yaml).

Run with it using:

```sh
make run-pocketbase CONF=doc/agiladmin.pocketbase.yaml
```

Or directly:

```sh
AGILADMIN_CONF=doc/agiladmin.pocketbase.yaml clj -M:run
```

PocketBase is optional in config, but without either PocketBase or `AGILADMIN_DEV_AUTH=1`, authentication is not initialized and login will not work.

Agiladmin expects the PocketBase `users` auth collection to have a `role` select field. Supported values are `admin`, `manager`, or empty.

Role-based features are enabled from the PocketBase user record returned at login. If a user role changes in PocketBase, they need to log out and log back in before Agiladmin sees the change.

Initialize the users collection field on a fresh PocketBase instance with:

```sh
AGILADMIN_CONF=doc/agiladmin.pocketbase.yaml clj -M -m agiladmin.pocketbase-init
```

If `agiladmin.pocketbase.manage-process` is `true`, Agiladmin starts PocketBase itself, serves it with the configured migrations directory, waits for health, applies the role bootstrap when the installed Agiladmin version changes, and stops PocketBase again on exit.

PocketBase HTTP calls use bounded timeouts by default so startup does not hang forever if the service is unreachable. Override them with `agiladmin.pocketbase.connect-timeout-ms` and `agiladmin.pocketbase.socket-timeout-ms` if needed.

## Testing

Run the test suite with:

```sh
clj -M:test
```

Or:

```sh
make test
```

The current test suite covers:

- config loading and validation
- spreadsheet ingestion and cost derivation
- auth backends and session behavior
- selected view logic
- `ring/init` startup behavior

It does not comprehensively cover route behavior, Git push side effects, or frontend rendering.

## Building

Build the standalone jar with:

```sh
make build
```

This produces:

```text
target/0.4.0-SNAPSHOT-standalone.jar
```

## Configuration

By default the app looks for `agiladmin.yaml` in standard locations,
including the current working directory. You can also point to an
explicit YAML file path with `AGILADMIN_CONF`.

Example:

```yaml
appname: agiladmin

agiladmin:
  webserver:
    host: localhost
    port: 8000
    anti-forgery: false
    ssl-redirect: false

  budgets:
    git: ssh://git@example.org/admin-budgets
    ssh-key: id_rsa
    path: budgets/

  source:
    git: https://github.com/dyne/agiladmin
    update: false

  pocketbase:
    base-url: http://127.0.0.1:8090
    users-collection: users
    superuser-email: admin@example.org
    superuser-password: change-me
```

Notes:

- `budgets.ssh-key` is the private key path used for Git access; if it does not exist, Agiladmin generates a new keypair and exposes the public key in the `/config` page
- project names are discovered from `*.yaml` files in `budgets.path`, using the part of the filename before the first `.`
- `pocketbase` is optional only if you are using dev auth locally

## Project Configuration

Each project is discovered from a YAML file in the budgets path, usually
`<PROJECT>.yaml`.

Minimal example:

```yaml
CORE:
  start_date: 01-01-2025
  duration: 12
  cph: 50

  rates:
    A.User: 55
    B.User: 45

  tasks: []
```

Task-based example:

```yaml
CORE:
  start_date: 01-01-2025
  duration: 12
  cph: 50

  rates:
    A.User: 55

  tasks:
    - id: T1
      text: Coordination
      start_date: 01-01-2025
      duration: 12
      pm: 1
```

Notes:

- task ids are normalized to uppercase internally
- the loader also accepts a direct-entry file shape where the file contains the project entry itself rather than a top-level project key
- spreadsheet parsing is position-based and depends on the current Excel template layout

## Repository Layout

- [src/agiladmin/handlers.clj](/home/jrml/devel/planb-agiladmin/src/agiladmin/handlers.clj): main HTTP routes
- [src/agiladmin/ring.clj](/home/jrml/devel/planb-agiladmin/src/agiladmin/ring.clj): initialization and middleware defaults
- [src/agiladmin/core.clj](/home/jrml/devel/planb-agiladmin/src/agiladmin/core.clj): spreadsheet and project logic
- [src/agiladmin/view_timesheet.clj](/home/jrml/devel/planb-agiladmin/src/agiladmin/view_timesheet.clj): upload and Git commit flow
- [src/agiladmin/auth/pocketbase.clj](/home/jrml/devel/planb-agiladmin/src/agiladmin/auth/pocketbase.clj): PocketBase auth backend
- [pb_migrations/](/home/jrml/devel/agiladmin/pb_migrations): PocketBase schema migrations kept for future schema changes
- [test/agiladmin/](/home/jrml/devel/planb-agiladmin/test/agiladmin): Midje test suite

## Operational Notes

- Timesheet upload and commit logic writes temporary files under `/tmp/...`
- The budgets repository is mutable application state; timesheet submission performs Git operations
- PocketBase-backed role-aware access depends on a `role` select field on the auth users collection
- Managed PocketBase mode uses a local version marker file to record that the current Agiladmin version has applied its bootstrap step
- The app serves a bundled static HTML README on `/`, so updating this file does not automatically change the in-app landing page

## License

Copyright (C) 2016-2026 Dyne.org foundation

Sourcecode written and maintained by Denis Roio <jaromil@dyne.org>

Designed in cooperation with Manuela Annibali <manuela@dyne.org>

![](https://files.dyne.org/software_by_dyne.png)
