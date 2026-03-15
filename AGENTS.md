# Repository Guide

## Overview
- `planb-agiladmin` is a Clojure CLI (`deps.edn`) web application for payroll and project administration.
- The app reads monthly `.xlsx` timesheets, loads project definitions from YAML files in a separate budgets repository, derives hours and costs, and renders HTML reports.
- The storage model is mixed:
  - project metadata and uploaded spreadsheets live in a Git-backed budgets directory;
  - authentication goes through an auth boundary, with PocketBase currently implemented and a dev-only fallback for local testing.

## Stack
- Language: Clojure 1.12.4
- Build tool: Clojure CLI (`deps.edn`)
- Web: Ring + Compojure
- HTML: Hiccup-style vectors rendered by view namespaces
- Data processing: Incanter datasets, Docjure/Apache POI for Excel, YAML parsing via `yaml.core`
- Auth: PocketBase via `src/agiladmin/auth/core.clj`, plus a development auth backend
- Git integration: `clj-jgit`

## Entry Points
- Main HTTP routes are in `src/agiladmin/handlers.clj`.
- Application initialization is in `src/agiladmin/ring.clj`.
  - `ring/init` loads configuration, ensures the SSH key exists, connects to MongoDB, and initializes auth stores.
- Core spreadsheet and project logic is in `src/agiladmin/core.clj`.
- The main user-facing views are split by domain:
  - `src/agiladmin/view_project.clj`
  - `src/agiladmin/view_person.clj`
  - `src/agiladmin/view_timesheet.clj`
  - `src/agiladmin/view_auth.clj`
  - `src/agiladmin/view_reload.clj`
  - shared rendering helpers in `src/agiladmin/webpage.clj` and `src/agiladmin/graphics.clj`

## Repository Layout
- `src/agiladmin/`: application source
- `resources/`: static assets, translations, and frontend JS/CSS
- `test/agiladmin/`: Midje tests
- `test/assets/`: fixture YAML and Excel files used by tests
- `doc/`: notes and reference material
- `timesheetpy/`: empty in this checkout; not part of the active app flow

## Configuration Model
- Runtime config is expected in `agiladmin.yaml`.
- Config loading is implemented in `src/agiladmin/config.clj`.
- `config-read` looks in several locations, including the current working directory; the repo root is the practical default during local development.
- Important top-level config keys under `:agiladmin`:
  - `:projects`
  - `:budgets`
  - `:webserver`
  - `:source`
  - `:just-auth`
- Project configs are separate YAML files stored under the configured budgets path and loaded by `load-project`.
- Tests use fixture config under `test/assets/agiladmin.yaml`.

## Runtime Assumptions
- The app expects access to:
  - a writable budgets Git repository;
  - an SSH private key path from config, generating one if missing;
  - a reachable PocketBase instance for real auth flows, unless `AGILADMIN_DEV_AUTH=1` is enabled.
- Timesheet upload and commit logic assumes a Unix-style temp path `/tmp/...` in `src/agiladmin/view_timesheet.clj`. That is a portability risk on Windows.
- Session cookie configuration depends on `@ring/config`; changes to init order can break middleware setup.

## Current Role Logic
- Accounts are normalized from the auth backend response in `src/agiladmin/session.clj`.
- Supported roles are `admin`, `manager`, or empty / nil.
- `admin` can access the personnel list, project area, reload page, configuration page, and project editing.
- `manager` can access the project area, but `/persons/list` resolves to that manager's own person page instead of the global personnel list.
- Empty-role or regular accounts are limited to their own person page.
- The authenticated home target is `/persons/list`; route dispatch decides whether that becomes the personnel list or a single person view.

## Developer Workflow
- Likely local start command: `clj -M:run`
- Main test path from `deps.edn`:
  - alias: `clj -M:test`
  - Midje runner namespace: `test/agiladmin/test_runner.clj`
- In this environment, Clojure CLI commands need their config/cache paths redirected into writable locations before dependency resolution.
- Frontend assets now use a minimal Node build:
  - install once with `npm install`
  - build CSS and sync the local HTMX asset with `npm run build:frontend`
  - Tailwind input lives at `resources/tailwind.css`
  - generated stylesheet is `resources/public/static/css/app.css`

## Testing Reality
- Existing tests are fixture-heavy and narrow.
- Covered areas:
  - config parsing and schema validation
  - spreadsheet ingestion and cost derivation
  - auth backends and session behavior
  - selected route and view behavior
  - minimal `ring/init` smoke test
- Not well covered:
  - HTTP route behavior
  - auth flows
  - Git push/commit side effects
  - frontend rendering behavior

## Codebase Conventions
- Most domain work happens on Incanter datasets rather than plain sequences.
- Failures are often represented with `failjure`; keep return types consistent when touching these paths.
- Project and task identifiers are normalized to uppercase in several paths. Preserve that behavior when changing import or matching logic.
- The codebase is old and not aggressively refactored. Prefer targeted fixes over stylistic rewrites.

## High-Risk Areas
- `src/agiladmin/core.clj`
  - spreadsheet parsing is position-based and depends on hard-coded row/column coordinates.
- `src/agiladmin/view_timesheet.clj`
  - upload, temp-file handling, Git add/commit/push, and filesystem assumptions are all coupled.
- `src/agiladmin/ring.clj`
  - startup performs real side effects: config load, SSH key generation, Mongo connection, auth initialization.
- `src/agiladmin/config.clj`
  - config merging and schema handling are permissive and a bit irregular; changes here can affect every feature.

## Guidance For Future Agents
- Read the relevant view namespace plus `core.clj` before changing behavior. Many screens are thin wrappers around shared dataset logic.
- Keep root navigation and navbar home links aligned with the `/persons/list` landing behavior for authenticated users.
- When changing spreadsheet parsing, validate against `test/assets/2016_timesheet_Luca-Pacioli.xlsx` and the expectations in `test/agiladmin/timesheet_test.clj`.
- When changing config handling, verify both global config loading and per-project YAML loading.
- Be conservative around `view_timesheet/commit`; it mutates the budgets repo and pushes over SSH.
- Avoid “cleanup” changes that rename columns, normalize casing differently, or alter dataset shapes unless you also update all dependent views/tests.
- Frontend styling uses TailwindCSS + DaisyUI with the `nord` theme; shared layout helpers live in `src/agiladmin/webpage.clj`.
- HTMX is loaded locally from `resources/public/static/js/htmx.min.js` and is intended for progressive enhancement only; keep full-page fallback behavior working.
- `resources/public/static/js/app.js` replaces the old Bootstrap JS for navbar toggles and tab switching.
- DHTMLX Gantt remains a JS island. Do not rewrite it into HTMX; only change the surrounding shell unless the task explicitly calls for deeper work.

## Useful Files
- [`README.md`](/C:/Users/denis/devel/planb-agiladmin/README.md)
- [`deps.edn`](/home/jrml/devel/planb-agiladmin/deps.edn)
- [`project.clj`](/home/jrml/devel/planb-agiladmin/project.clj)
- [`src/agiladmin/handlers.clj`](/C:/Users/denis/devel/planb-agiladmin/src/agiladmin/handlers.clj)
- [`src/agiladmin/ring.clj`](/C:/Users/denis/devel/planb-agiladmin/src/agiladmin/ring.clj)
- [`src/agiladmin/core.clj`](/C:/Users/denis/devel/planb-agiladmin/src/agiladmin/core.clj)
- [`src/agiladmin/config.clj`](/C:/Users/denis/devel/planb-agiladmin/src/agiladmin/config.clj)
- [`src/agiladmin/view_timesheet.clj`](/C:/Users/denis/devel/planb-agiladmin/src/agiladmin/view_timesheet.clj)
- [`test/agiladmin/timesheet_test.clj`](/C:/Users/denis/devel/planb-agiladmin/test/agiladmin/timesheet_test.clj)
