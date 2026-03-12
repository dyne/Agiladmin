# Agiladmin

Agiladmin is a Clojure web application for payroll, timesheet, and
project administration. It imports monthly `.xlsx` timesheets, loads
project definitions from YAML files in a Git-backed budgets
repository, computes hours and costs, and renders HTML reports for
personnel and projects.

The current codebase targets `org.clojure/clojure` `1.12.4` and starts
with the Clojure CLI. Authentication is backend-driven: PocketBase and
Pocket ID are supported for real deployments, and a development-only
fallback backend is available for local manual testing.

## Current State

- Runtime: Ring + Compojure on Jetty
- Data processing: core.matrix, Docjure / Apache POI, YAML files
- Storage model:
  - project metadata and uploaded spreadsheets live in a Git-managed budgets directory
  - authentication is handled through a backend abstraction, with PocketBase and Pocket ID adapters
- Build: Clojure CLI with `deps.edn`
- Tests: Midje

The app is well tested and fairly stateful. Startup performs real side effects:

- configuration is loaded from YAML
- an SSH keypair is generated if the configured private key does not exist
- the authentication backend is initialized and health-checked

## Requirements

- Java (JRE) and the Clojure CLI
- a writable budgets Git checkout or clone target in `budgets/`
- a valid `agiladmin.yaml` configuration file, or an explicit config path via `AGILADMIN_CONF`
- for real auth flows: either a reachable PocketBase instance or a reachable Pocket ID issuer

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

- `admin:admin`
- `manager:manager`
- `guest:guest`

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

PocketBase is optional in config, but without either an auth backend or `AGILADMIN_DEV_AUTH=1`, authentication is not initialized and login will not work.

Agiladmin expects the PocketBase `users` auth collection to have a `role` select field. Supported values are `admin`, `manager`, or empty.

Role-based features are enabled from the PocketBase user record returned at login. If a user role changes in PocketBase, they need to log out and log back in before Agiladmin sees the change.

Current role logic:

- `admin`: can reach the personnel list, project views, reload, configuration, and project editing
- `manager`: can reach project views and is routed to their own personnel page when opening the personnel landing
- empty role or regular user: is limited to their own personnel page
- the shared home entry point for authenticated users is `/persons/list`; that route sends admins to the personnel list and everyone else to their own person view

Initialize the users collection field on a fresh PocketBase instance with:

```sh
AGILADMIN_CONF=doc/agiladmin.pocketbase.yaml clj -M -m agiladmin.pocketbase-init
```

If `agiladmin.pocketbase.manage-process` is `true`, Agiladmin starts PocketBase itself, serves it with the configured migrations directory, waits for health, applies the role bootstrap when the installed Agiladmin version changes, and stops PocketBase again on exit.

PocketBase HTTP calls use bounded timeouts by default so startup does not hang forever if the service is unreachable. Override them with `agiladmin.auth.pocketbase.connect-timeout-ms` and `agiladmin.auth.pocketbase.socket-timeout-ms` if needed.

### Running With Pocket ID

The repository also ships a Pocket ID example config at [doc/agiladmin.pocket-id.yaml](/home/jrml/devel/agiladmin/doc/agiladmin.pocket-id.yaml).

Run with it using:

```sh
AGILADMIN_CONF=doc/agiladmin.pocket-id.yaml clj -M:run
```

Create an OIDC client in Pocket ID with this redirect URI:

```text
https://<your-agiladmin-host>/auth/pocket-id/callback
```

Recommended scopes are:

```text
openid profile email groups
```

Agiladmin checked Pocket ID documentation on 2026-03-12 and uses the standard OIDC authorization code flow with PKCE, a shared client secret, and role mapping from groups. Configure two Pocket ID groups and map them into Agiladmin with `admin-group` and `manager-group`. If a user belongs to both groups, `admin` wins.

Pocket ID login is redirect-based. The Agiladmin login page shows a single “Sign in with Pocket ID” action and no local password form when this backend is active.

Logout is local-only for now: Agiladmin clears its Ring session and redirects back to `/login`. The Pocket ID session may still be active in the browser.

Pocket ID does not power Agiladmin signup, activation, or pending-user admin flows. Those actions stay in Pocket ID.

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

### Run Standalone Jar

Run the built jar with:

```sh
java -jar target/<version>-standalone.jar
```

Or with an explicit config file:

```sh
AGILADMIN_CONF=doc/agiladmin.pocketbase.yaml java -jar target/<version>-standalone.jar
```

Or:

```sh
AGILADMIN_CONF=doc/agiladmin.pocket-id.yaml java -jar target/<version>-standalone.jar
```

The jar uses the same config lookup as `clj -M:run`: by default it looks for `agiladmin.yaml` in the standard locations, including the current working directory.

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

  auth:
    backend: pocket-id
    pocket-id:
      issuer-url: https://pocket-id.example.org
      client-id: agiladmin
      client-secret: change-me
      redirect-uri: https://agiladmin.example.org/auth/pocket-id/callback
      admin-group: agiladmin-admin
      manager-group: agiladmin-manager
      scopes:
        - openid
        - profile
        - email
        - groups
```

Notes:

- `budgets.ssh-key` is the private key path used for Git access; if it does not exist, Agiladmin generates a new keypair and exposes the public key in the `/config` page
- project names are discovered from `*.yaml` files in `budgets.path`, using the part of the filename before the first `.`
- `agiladmin.auth.backend` may be `pocketbase`, `pocket-id`, or `dev`
- legacy top-level `agiladmin.pocketbase` config is still normalized into `agiladmin.auth.pocketbase`

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
- [src/agiladmin/auth/pocket_id.clj](/home/jrml/devel/agiladmin/src/agiladmin/auth/pocket_id.clj): Pocket ID OIDC auth backend
- [pb_migrations/](/home/jrml/devel/agiladmin/pb_migrations): PocketBase schema migrations kept for future schema changes
- [test/agiladmin/](/home/jrml/devel/planb-agiladmin/test/agiladmin): Midje test suite

## Operational Notes

- Timesheet upload and commit logic writes temporary files under `/tmp/...`
- The budgets repository is mutable application state; timesheet submission performs Git operations
- PocketBase-backed role-aware access depends on a `role` select field on the auth users collection
- Pocket ID-backed role-aware access depends on the configured Pocket ID groups being present in ID token claims or `userinfo`
- Managed PocketBase mode uses a local version marker file to record that the current Agiladmin version has applied its bootstrap step
- The app serves a bundled static HTML README on `/`, so updating this file does not automatically change the in-app landing page

## License

Copyright (C) 2016-2026 Dyne.org foundation

Sourcecode written and maintained by Denis Roio <jaromil@dyne.org>

Designed in cooperation with Manuela Annibali <manuela@dyne.org>

![](https://files.dyne.org/software_by_dyne.png)
