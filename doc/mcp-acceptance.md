# MCP acceptance evidence

The acceptance scenario runs without live credentials, production storage or
SSH pushes. It uses the pinned official Java MCP SDK 1.1.3 client, protocol
2025-11-25, an ephemeral Jetty server, isolated file ledgers, verified test
accounts and a local bare Git remote. A test-only login route establishes an
HTTP browser session; the production review routes enforce owner identity and
CSRF. Production continues to reject development-auth MCP credentials.

`clojure -M:test-mcp` exercises the complete scenario:

1. Discover the `studio` organization alias and project A's `DEV` task.
2. Validate and save 360 + 240 minutes on one local calendar date. The default
   480-minute cap yields A 288 paid / 72 VOL and B 192 paid / 48 VOL.
3. Retry the identical request, correct a stable entry, restore the example,
   and check that the original retry receipt still returns revision 1 while
   the current draft is revision 3.
4. Create an immutable preview without writing the official annual workbook.
   Sign into the owner session over HTTP, reject a POST without CSRF, then
   submit the hidden approval evidence parsed from the actual HTML review.
5. Inject a push failure, observe the local commit and revision through MCP,
   and retry the same approval. Check the exact commit reached the bare remote
   and no duplicate workbook commit was created.
6. Reopen the saved workbook with `core/load-timesheet`, read its month with
   `core/load-monthly-hours`, and derive costs through the production reader.
   Paid hours total 8, VOL hours total 2; paid costs are 192 + 96 at the test
   rates, and both VOL assignments cost zero.
7. Repeat with a second credential owner using the same record/request IDs and
   a 300-minute owner override: 5 paid / 5 VOL hours, A 180 paid / 180 VOL,
   B 120 paid / 120 VOL. Its paid costs are 120 + 60. Neither owner can read
   the other's records.
8. Save eight distinct monthly assignments, reject preview with
   `column_overflow`, and verify all eight complete records remain in the draft.

The endpoint is exercised beneath `/payroll`; the HTTP harness explicitly
models proxy prefix stripping. It tests transport behavior on loopback HTTP,
not a deployed TLS proxy. Separate guards test the required HTTPS public
configuration. The browser suite covers actual Playwright login, owner
isolation, workbook download and no-JavaScript confirmation.

## Reproduction

On a normal development installation:

```sh
clojure -M:test-mcp
clojure -M:test
npm ci
npx playwright install chromium
npm run build:frontend
npm run test:e2e
npm run test:e2e:base-path
```

The managed environment used on 2026-10-07 has Java 21.0.12.1, Clojure 1.12.4,
Clojure CLI 1.12.4.1582, Node 24.21.0, npm 11.19.0 and Playwright 1.59.1
(the package-lock resolution). Its exact test commands are:

```sh
export CLJ_CONFIG=/tmp/agiladmin-clj-config
export CLJ_CACHE=/tmp/agiladmin-clj-cache
export CLOJURE_CMD=/tmp/agiladmin-cli/bin/clojure
export PLAYWRIGHT_BROWSERS_PATH=/tmp/agiladmin-playwright
/tmp/agiladmin-cli/bin/clojure -M:test-mcp
/tmp/agiladmin-cli/bin/clojure -M:test
npm run build:frontend
npm run test:e2e
npm run test:e2e:base-path
```

The focused runner also loads the prerequisite test fixture namespaces and
their checks; its final scenario count is the acceptance scenario alone.

## Recorded gates

| Gate | Result | Evidence |
| --- | --- | --- |
| Pinned client acceptance | Exit 0; one scenario, two owners, failed-push recovery | `/tmp/l6-focused.log` |
| Full Clojure suite | Exit 0; 1362 checks succeeded | `/tmp/l6-clojure.log` |
| Frontend build | Exit 0 | `/tmp/l6-build.log` |
| Browser suite | Exit 0; 35 passed, 1 prefix-only test skipped | `/tmp/l6-e2e.log` |
| Base-path browser suite | Exit 0; 6 passed | `/tmp/l6-prefix.log` |

The browser suite includes admin/manager legacy workbook uploads, byte identity
checks, displayed spreadsheet contents, and personnel/project report smoke
checks. The full Clojure suite also checks the original 2016 workbook reader
expectations. Its fixture SHA-256 remains
`a78069f5b6bad3970295aed77fd26a4abf19dd7a1d4be26fbe2069f73ea8440f`.

The screenshot matrix is regenerated under `output/playwright/work-review`:
review, overflow, stale and confirmation surfaces, each at 390×844 and
1440×900, with 100% and 200% root font size, unprefixed and prefixed (32 PNGs).
Tests assert no page overflow, escaped complete notes, visible paid/VOL totals,
keyboard focus and access to confirmation controls. This evidence does not
claim a production deployment, live PocketBase or live TLS verification.
The opt-in `AGILADMIN_PB_IT` integration checks were not enabled; skipped checks
are not passes.

## Onboarding example verification

L6.2 changes documentation only. The current full-suite/build/browser results
above are reused. The one-off isolated checker at `/tmp/l6-onboarding-check.clj`
loads the shipped `doc/agiladmin.mcp.yaml`, substituting only temporary storage
paths. It invokes the actual local credential CLI for provision/list/rotation/
revocation, with a verified test-account PocketBase catalog instead of live auth.
Token output is captured in memory. Because the one-shot CLI commands run in one
test JVM, their process shutdown hook is suppressed until the checker finishes.

It validates all ten `doc/mcp-work-example.json` input and actual output schemas,
runs the 6h+4h save and exact retry, follows owner-session/CSRF review through
local-remote publication, applies the documented 5h correction, confirms again,
checks pushed revision 2 and reopens the nine-hour official Excel report. All
checks passed, exit 0; evidence is `/tmp/l6-onboarding.log`. No deployed TLS or
live PocketBase credential command is claimed. New relative documentation links
were checked with zero broken targets; examples contain only explicit secret
placeholders.

Exact managed-environment command (with the environment exports above):

```sh
/tmp/agiladmin-cli/bin/clojure -Sdeps '{:aliases {:onboarding-check {:extra-paths ["test"] :extra-deps {ring/ring-mock {:mvn/version "0.3.2"} midje/midje {:mvn/version "1.10.10"}} :main-opts ["/tmp/l6-onboarding-check.clj"]}}}' -M:onboarding-check
```

Independent whole-branch terminal review remains a separate **pending** gate,
owned by the root after milestone acceptance. This evidence does not claim that
review has passed.
