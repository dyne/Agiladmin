# Daily work ledger boundary

`work-service/upsert!` receives the owner resolved by the authentication boundary,
the existing project catalog and policy. Callers supply only month, entries,
request ID and expected revision. Credentials and roles never change ownership.
`work-ledger/open-ledger!` requires a data directory outside budgets. Close the
adapter during shutdown; run exactly one writer process for that directory.
Storage uses server-generated SHA-256 account-ID filenames, with annual aggregates.
The directory must be writable only by the application/operator; it must not
contain symlinks. Atomic replacement and directory fsync must be supported by
the deployment filesystem. Startup/write failure must not be treated as success.

External IDs and request IDs are scoped to the owner across all annual files,
not to a token or month. A repeated identical client command (including its
original expected revision) returns the original result, even after subsequent
changes. Reusing a request ID for a changed command fails. Moving an existing ID
to another month/year fails; corrections must explicitly delete then insert.
Different external IDs are never silently deduplicated by matching contents.

Each committed EDN aggregate includes records, month revisions, audit history,
and request receipts. Writes force temporary file bytes, atomically replace the
aggregate, then force the directory before returning success. A forced backup
of the prior valid aggregate is retained. Corrupt/unsupported aggregates block
reads and mutations; the adapter never silently substitutes a backup. Operators
must investigate and explicitly restore validated backup data with the writer
stopped. Back up the whole directory to preserve owner-wide identity/receipts.

A failure before replacement preserves prior records. A failure after replacement
(including lost response or directory-fsync failure) can leave a committed command
whose acknowledgement is uncertain. Retry the exact original request ID/payload;
its receipt reconciles the outcome without applying the command again. Do not
invent a new request ID to resolve uncertain outcomes. The adapter never promises
that every failed request has had no effect.

Corrections recompute the complete month from current records under the current
policy. Deletes retain the previous record in audit history and a tombstone with
its external ID. Missing IDs fail the whole batch; no other owner's record is
looked up. An explicit delete permits reinsertion in another month or year.
An old successful upsert retry returns its historical receipt and never restores
a subsequently deleted record. Each successful batch advances only its month
revision, even if its records are identical to the current draft.

`month-snapshot` returns immutable, deterministically sorted records, complete
daily allocation, monthly totals, capacity and policy hash with a content digest.
Retain that value when preparing an artifact; `snapshot-page` paginates it without
mixing revisions. `get-month` instead reads the live snapshot and rejects a cursor
if any revision, content or policy changed. Cursors carry a digest and bounded
offset; they confer no ownership authority. `check-snapshot` returns the current
exact snapshot only when ownership/digest match and export capacity permits it.
Publication must repeat this gate while holding its own mutation locks.
Snapshot arguments are server-produced values, never caller-supplied snapshot
objects. A policy change invalidates previews and live cursors; an identical
command retry still returns its original policy-bound receipt.

Eight or more assignment columns remain a valid saved draft, but export is
blocked. Duplicate-looking records with distinct IDs remain stored and are
reported in `possible-duplicate-ids` for human review.
