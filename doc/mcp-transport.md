# MCP transport and owner credentials

The existing Ring server exposes `/mcp` when `:agiladmin :mcp :enabled` is true,
the separate `:data-path` is valid, production account resolution is available,
and `:agiladmin :webserver :base-host` is an HTTPS origin. The default is disabled.
There is no second service. The proxy strips the configured public `base-path`
and preserves the public Host header. Forwarded Host and Origin headers are not
used as authority. Origin, when supplied, must equal the configured HTTPS origin.
TLS termination belongs to the existing HTTPS proxy.

Enabling or disabling MCP, changing its public origin, request limits or storage
path requires restarting the application. Runtime policy and cap changes are
read for each tool request; changing `:enabled` alone does not stop an already
started endpoint.

The pinned official Java SDK 1.1.3 advertises only protocol `2025-11-25`.
Initialize negotiates that version; subsequent POSTs require its
`MCP-Protocol-Version` header. Responses use JSON, notifications receive 202,
and GET/DELETE receive 405. Clients send `Content-Type: application/json` and
`Accept: application/json, text/event-stream`. No SSE session is needed for
these request/response tools.

Limits are 262144 request bytes and 60 requests per minute both per source
address and per owner (including rotated credentials). Each limiter retains at
most 1024 active buckets; full buckets fail closed. A shared proxy source can
therefore impose a stricter total limit. The adapter does not trust forwarded
IP headers. Responses include `Retry-After: 60` when throttled.

Run credential lifecycle commands locally under the application operator's
account, with Clojure CLI configured for the deployment:

```sh
clojure -M -m agiladmin.mcp.credential-cli agiladmin provision ACCOUNT_ID 2027-01-01T00:00:00Z
clojure -M -m agiladmin.mcp.credential-cli agiladmin list
clojure -M -m agiladmin.mcp.credential-cli agiladmin revoke CREDENTIAL_ID
```

Provision prints a fresh 256-bit bearer token once. Save it in the personal
agent's secret store and configure a manual `Authorization: Bearer TOKEN`
header. Storage contains SHA-256 hashes, IDs, expiry and revocation metadata;
listing never returns hashes or tokens. The credential directory is mode 700,
files mode 600, with locked atomic replacement and backups. The credential
file has a 1 MiB capacity limit. Back up the configured data directory securely.

Every authenticated request resolves verified existing accounts through the
auth boundary and checks unique safe workbook identities. Deletion,
unverification, identity collisions, backend failure, expiry and revocation
fail closed. Development auth is rejected. Account role never permits acting
for someone else through MCP; IDs remain owner-scoped across token rotation.

This manual bearer mode does not implement MCP OAuth authorization; clients
that require OAuth-only discovery are unsupported. The endpoint exposes only
the seven work tools, with no evaluation, shell, arbitrary file, credential
administration or owner-confirmation tool. It never logs credentials, request
bodies or underlying auth exceptions. Browser authentication continues through
its existing session middleware; MCP dispatch runs outside form middleware.
The shipped SLF4J Simple configuration disables SDK diagnostics, which otherwise
include client-provided initialization metadata. Retain that restriction when
changing logging backends; do not enable raw SDK request diagnostics in production.

## Discovery and drafts

`get_work_context` returns the owner's effective paid cap, reporting timezone,
policy version/hash and a paginated catalog of project, task and organization
alias items. It never returns payroll rates, personnel lists or other owners'
cap overrides. Use `limit` (1–200, default 50) and the returned `next_cursor`;
catalog or owner changes invalidate the cursor. `get_month` similarly paginates
authoritative entries and refuses mixing different revisions/policies.

Every tool publishes explicit input and output schemas, examples, units,
side-effect/retry guidance and annotations. Results include equivalent text and
structured JSON. Domain failures set `isError` and return `error.code`, `field`,
`explanation` and `next_action`. Invalid JSON-RPC envelopes/methods/params use
protocol errors. The static `agiladmin://work/workflow` resource and optional
`prepare_month` prompt repeat the workflow; every tool description and the
context result also explain it for tools-only clients.

Validation merges proposed upserts with the current complete month without
writing records, receipts, audit or preview files. It resolves canonical IDs,
checks cross-month IDs across years, recalculates complete days and reports
monthly paid/VOL column usage. Mutations take one month, a current
`expected_revision`, and an owner-scoped `request_id`; at most 200 records or IDs
per batch. Identical retries retain their original result even after later
corrections. Reusing a request ID with changed arguments fails. Different record
IDs remain distinct and possible duplicates require human review.

Draft overflow is retained and reported with explicit consolidation guidance.
No tool automatically drops projects, tasks, notes or hours. A preview refuses
more than seven monthly project/task/tag combinations or an unmanaged populated
legacy month. Official reports continue to read the unchanged official workbook
until the owner confirms through the authenticated browser publication flow.

## Immutable previews and the browser integration boundary

`preview_month` retains a private immutable artifact for 30 minutes. It binds
the owner, full exact snapshot, revision, policy hash, annual source fingerprint,
target-month baseline, workbook fingerprint and exact changed cells. It writes
only the configured data directory. The workbook and safe EDN metadata are
forced to disk, then an atomic directory rename publishes both. Reads verify
metadata/bytes, reject symlinks and enforce ownership; neither the agent nor a
tool argument can select a filesystem path.

`artifact_url` points beneath the public base-path to
`GET /mcp/artifacts/PREVIEW_ID`. Send the same bearer header; authentication,
Host/Origin and rate guards apply to downloads. Another owner receives the same
unavailable response as a missing artifact. Downloaded bytes remain the original
preview even after draft/policy changes. Expired previews require regeneration.
`owner_review_url` points to `/work/review/PREVIEW_ID` for the owner browser flow.
There is no bearer or MCP approval operation.

The trusted server interface for that browser flow is
`work-preview/read-preview`, `artifact`, and `verify-preview`. The retained
evidence is private server data. `verify-preview` rechecks owner, expiry, current
draft/policy, source fingerprint and target-month baseline without writes;
publication invokes it again under its mutation locks. A live check
also runs before a newly created preview URL is returned.

The composition root installs two server-owned functions through
`mcp.runtime/install-publication!`: `read-baseline(owner, month)` and
`publication-status(owner, month)`. Managed evidence never comes from agents.
Until installed, no existing populated month can be adopted and status reports
draft. Status responses allow only month/revisions, publication phase, commit,
push state and repair guidance; private ledger, filesystem and credential
details are omitted. The baseline reader must combine the latest annual
fingerprint with the target month's managed baseline when another month in that
year has been published. The MCP adapter maps the actual publication revision and
commit ID into `published_revision` and `commit`, and reports its real push state.

For complete operator onboarding, confirmation, backups and recovery limits,
see [the operation guide](mcp-operation.md). Reproducible isolated workflow and
browser evidence are in [acceptance evidence](mcp-acceptance.md).
