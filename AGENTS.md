# Repository Guide

## Overview
- `planb-agiladmin` is a Clojure CLI (`deps.edn`) web application for payroll and project administration.
- The app reads monthly `.xlsx` timesheets, loads project definitions from YAML files in a separate budgets repository, derives hours and costs, and renders HTML reports.
- The storage model is mixed:
  - project metadata and uploaded spreadsheets live in a Git-backed budgets directory;
  - authentication goes through an auth boundary, with PocketBase currently implemented and a dev-only fallback for local testing.
  - optional personal MCP work drafts, receipts, credentials, previews and publication evidence live in a private file ledger outside budgets; official reports continue to read Excel.

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
  - `ring/init` loads configuration, ensures the SSH key exists, and initializes/health-checks auth stores.
- Core spreadsheet and project logic is in `src/agiladmin/core.clj`.
- MCP composition and transport are in `src/agiladmin/mcp/runtime.clj`, `http.clj` and `tools.clj`; local credential lifecycle is in `credential_cli.clj`.
- Daily work boundaries are `work_policy.clj`, `work_service.clj`, `work_ledger.clj`, `work_workbook.clj`, `work_preview.clj`, `work_publication.clj` and `work_archive.clj`. Browser review is `view_work.clj`.
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
  - `:cache` enables runtime in-memory caches when `true`; the default is `false`.
  - `:voluntary-hours` controls whether personnel monthly summaries mention voluntary hours; default `false`.
  - `:vat-percentage` controls personnel VAT display; default `0`, which hides VAT text.
- `:agiladmin :webserver` now separates internal bind settings from public URL settings:
  - `:host` and `:port` are Jetty bind values.
  - `:base-host` and `:base-path` are browser-facing URL parts.
  - `:upload-max-size` configures upload byte limits (default `500000`).
- Project configs are separate YAML files stored under the configured budgets path and loaded by `load-project`.
- `:agiladmin :mcp` is disabled by default. Enabled settings require a private `:data-path` outside budgets, production account resolution and an HTTPS `:webserver :base-host`. Policy keys are `:timezone`, `:paid-cap-minutes`, `:person-cap-overrides` (stable account IDs) and `:organization-aliases` (existing project IDs).
- Config files are loaded on startup; the Reload page refreshes budgets/caches, not application YAML. Restart after editing endpoint/storage/auth settings. Current policy changes invalidate previews.
- Tests use fixture config under `test/assets/agiladmin.yaml`.

## Runtime Assumptions
- The app expects access to:
  - a writable budgets Git repository;
  - an SSH private key path from config, generating one if missing;
  - a reachable PocketBase instance for real auth flows, unless `AGILADMIN_DEV_AUTH=1` is enabled.
- Timesheet upload and commit logic assumes a Unix-style temp path `/tmp/...` in `src/agiladmin/view_timesheet.clj`. That is a portability risk on Windows.
- Session cookie configuration depends on `@ring/config`; changes to init order can break middleware setup.
- MCP is single-writer: one process per ledger and budgets checkout. Its process lock does not protect against external checkout edits. Use temporary ledgers/local bare remotes for tests; never use live credentials or a real budgets push.
- Production rejects dev-auth MCP credentials. The browser harness's verified test-account seam is test-only.

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
- Focused complete MCP workflow: `clojure -M:test-mcp` (pinned Java client, isolated ledger/auth, session/CSRF confirmation, local Git recovery and production readers).
- In this environment, Clojure CLI commands need their config/cache paths redirected into writable locations before dependency resolution.
- Frontend assets now use a minimal Node build:
  - install once with `npm install`
  - build CSS and sync the local HTMX asset with `npm run build:frontend`
  - Tailwind input lives at `resources/tailwind.css`
  - generated stylesheet is `resources/public/static/css/app.css`
- Browser E2E coverage uses Playwright:
  - first-time setup: `npm ci` then `npx playwright install chromium`
  - run suite: `npm run test:e2e` (dev auth harness, no PocketBase)

## Testing Reality
- Existing tests are fixture-heavy and narrow.
- Covered areas:
  - config parsing and schema validation
  - spreadsheet ingestion and cost derivation
  - auth backends and session behavior
  - selected route and view behavior
  - minimal `ring/init` smoke test
  - browser login/upload path for admin and manager via Playwright harness
  - MCP lifecycle, schemas, owner credentials, retries/corrections, integer allocations and immutable previews
  - temporary Git publication, failed-push/crash recovery, annual/repository serialization and original/generated workbook round trips
  - browser review/overflow/stale/confirmation matrix at mobile/desktop and 100%/200% font size, with prefix, keyboard and no-JavaScript checks
- Not well covered:
  - live PocketBase, deployed HTTPS proxies and live SSH pushes (opt-in/external checks)
  - automatic reconciliation of divergent remote history or legacy populated months (unsupported in v1)

## Codebase Conventions
- Most domain work happens on Incanter datasets rather than plain sequences.
- Failures are often represented with `failjure`; keep return types consistent when touching these paths.
- Project and task identifiers are normalized to uppercase in several paths. Preserve that behavior when changing import or matching logic.
- The codebase is old and not aggressively refactored. Prefer targeted fixes over stylistic rewrites.

## Visualization Ownership
- `agiladmin.tabular` remains the domain and application table format.
- `src/agiladmin/visualization.clj` is the adapter to Tablecloth and owns all production Tableplot/Plotly specifications.
- Clay is limited to local exploration and reviewer reports under the `:viz` alias; never invoke it during HTTP request handling.
- Plotly.js is a local frontend asset initialized by `resources/public/static/js/app.js` on page load and `htmx:load`.
- Chart models must follow the same role and configuration capabilities as their surrounding views. Manager payloads are hours-only.
- Keep existing detail tables authoritative beneath charts and leave the DHTMLX Gantt island unchanged.
- Available activity facts are monthly assignment totals. Do not infer daily, weekly, or within-month activity without a separate parser change.

## High-Risk Areas
- `src/agiladmin/core.clj`
  - spreadsheet parsing is position-based and depends on hard-coded row/column coordinates.
- `src/agiladmin/view_timesheet.clj`
  - upload, temp-file handling, Git add/commit/push, and filesystem assumptions are all coupled.
- `src/agiladmin/ring.clj`
  - startup performs real side effects: config load, SSH key generation and auth initialization/health checks.
- `src/agiladmin/config.clj`
  - config merging and schema handling are permissive and a bit irregular; changes here can affect every feature.
- `src/agiladmin/work_publication.clj` / `work_archive.clj`
  - approval evidence binds exact revision/policy/workbook fingerprints. Preserve owner → annual → budgets lock order, exact-commit non-force pushes, staged-path isolation and cache invalidation after local changes even when push fails.

## Guidance For Future Agents
- Read the relevant view namespace plus `core.clj` before changing behavior. Many screens are thin wrappers around shared dataset logic.
- Keep root navigation and navbar home links aligned with the `/persons/list` landing behavior for authenticated users.
- When changing spreadsheet parsing, validate against `test/assets/2016_timesheet_Luca-Pacioli.xlsx` and the expectations in `test/agiladmin/timesheet_test.clj`.
- When changing config handling, verify both global config loading and per-project YAML loading.
- Project configs are cached in memory by budgets path in `src/agiladmin/core.clj`; invalidate that cache when a flow adopts new repo contents, currently via `view_reload.clj`.
- Timesheet discovery and caching now assume direct-child workbook files under the budgets root; `src/agiladmin/view_timesheet.clj` invalidates the in-memory timesheet cache after a successful archive/push.
- Be conservative around `view_timesheet/commit`; it mutates the budgets repo and pushes over SSH.
- Avoid “cleanup” changes that rename columns, normalize casing differently, or alter dataset shapes unless you also update all dependent views/tests.
- Frontend styling uses TailwindCSS + DaisyUI with the `nord` theme; shared layout helpers live in `src/agiladmin/webpage.clj`.
- HTMX is loaded locally from `resources/public/static/js/htmx.min.js` and is intended for progressive enhancement only; keep full-page fallback behavior working.
- Current HTMX seams follow the same pattern: the normal route remains authoritative and returns a full page, while `web/htmx-request?` switches selected actions to fragment responses. Existing examples are `POST /reload`, `POST /timesheets/upload`, and `POST /project`.
- `resources/public/static/js/app.js` replaces the old Bootstrap JS for navbar toggles and tab switching.
- Browser-facing app URLs should be generated via `agiladmin.webpage/path` / `asset-path` helpers rather than hard-coded `"/..."` strings; route definitions remain root paths and reverse proxies are expected to strip any configured public `base-path`.
- DHTMLX Gantt remains a JS island. Do not rewrite it into HTMX; only change the surrounding shell unless the task explicitly calls for deeper work.
- Daily MCP data uses integer minutes and stable owner-scoped IDs; do not infer daily activity from monthly report totals. Upserts replace complete record fields; corrections need fresh request IDs/current revisions, while uncertain retries keep identical arguments.
- Keep seven B:H monthly project/task/tag columns and literal complete notes in I. Retain/report overflow; never consolidate automatically or alter original workbook fixtures.
- MCP bearer credentials cannot approve publication. Browser confirmation requires owner session and CSRF even if general form anti-forgery configuration is disabled.
- Normalize publication adapter revision/commit fields into the advertised MCP status schema; test real publication results, not only mocked wire-shaped status maps.
- Operator setup/recovery is documented in `doc/mcp-operation.md`, example config/tool calls in `doc/agiladmin.mcp.yaml` and `doc/mcp-work-example.json`, and verification in `doc/mcp-acceptance.md`. Remote divergence needs operator reconciliation; do not force/reset away remote history or edit private EDN to bypass conflicts.

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
