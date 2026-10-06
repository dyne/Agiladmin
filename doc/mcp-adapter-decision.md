# Daily work boundary and MCP adapter

The daily work ledger is the core domain. Its owner/year aggregate contains
records and month revisions; allocation is a pure person/day rule across projects.
Legacy Excel reports remain a separate model behind the workbook boundary.
Authentication and MCP are supporting adapters: credentials establish an account
ID, never a caller-selected person. `agiladmin.work-policy` owns input validation
and policy; `agiladmin.work-ports` defines the ledger, workbook and publication
boundaries. Persistence, credentials, publication and the production route are
implemented in subsequent milestones.

## Decision

Use the official Java SDK's stateless server and a small Ring Streamable HTTP
bridge, with `mcp-core` and `mcp-json-jackson2` pinned to **1.1.3**. Advertise only
protocol **2025-11-25**. The SDK release tag resolves to commit
`f4c25f00f63a4a0c6ad098bfb774a7a94574bc9c`. Keep Ring 1.7.1,
Jetty 9.4.12.v20180830 and javax.servlet 3.1.0.
JSON POST responses, 202 notifications and 405 GET/DELETE suit these
request/response tools; no SSE session or second service is required. Production
must implement protocol-header checks, bounded bodies, Origin/Host validation,
owner credentials and request error handling before enabling `/mcp`. The probe
is a test fixture and does not claim those production protections.

`test/agiladmin/mcp_compatibility_test.clj` builds the SDK stateless handler,
starts the actual Ring Jetty adapter on a temporary loopback port, and connects
the pinned SDK's real HTTP client. It exercises initialization, initialized
notification, ping, tools/list, a fixture tool call with structured output, and
negotiation from an unsupported requested version to 2025-11-25. Separate JSON
schema checks verify both accepted and rejected results. The fixture exposes
only `compatibility_echo`; it never initializes the app or external auth.

## Dependency compatibility

The Jackson2 SDK module requires Jackson databind 2.20.1 and NetworkNT JSON
schema validator 2.0.0. NetworkNT's optional YAML support normally pulls
jackson-dataformat-yaml 2.18.3 and SnakeYAML 2.3. That breaks the existing
io.forward/yaml 1.0.11 reader's SnakeYAML 1.25 constructor ABI. Exclude
jackson-dataformat-yaml on the SDK dependency: MCP schemas use JSON, and both
JSON schema validation and existing YAML fixture loading are tested. Do not
use NetworkNT's YAML parsing in this application. Existing Cheshire JSON,
PocketBase, Ring and workbook behavior are covered by the full Midje suite.
The resolved tree retains SLF4J 1.7.26; the exercised SDK/validator paths work
with it. Future SDK functionality must be checked against that retained API.

## Alternative examined

Examined [bhauman/clojure-mcp](https://github.com/bhauman/clojure-mcp/tree/2b0213f079ab59e3b204c539ead12b63db54e223)
at exact revision `2b0213f079ab59e3b204c539ead12b63db54e223`.
Its `core.clj` provides custom component factories (`:make-tools-fn`,
`:make-prompts-fn`, `:make-resources-fn`) but the server bootstrap is organized
around an nREPL client and working directory. Its dependency set includes
nREPL and LangChain4j OpenAI, Anthropic and Gemini integrations. Its HTTP alias
adds Jakarta servlet 6.1.0 and Jetty 11.0.20. Reusing that bootstrap would add
unneeded developer/LLM dependencies and a servlet namespace migration;
the direct SDK interface is smaller. No upstream source is copied, and no
REPL, edit, file or shell tools enter production. The official SDK is MIT
licensed; retain its upstream notices when distributing dependencies.
The examined clojure-mcp reference is EPL-2.0 licensed; no source reuse or
copying is needed for this decision.

References: [SDK 1.1.3 release](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v1.1.3),
[Streamable HTTP 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports).

## Configuration contract

`:agiladmin :mcp` is optional. Defaults are `:enabled false`,
`:timezone "Europe/Rome"`, `:paid-cap-minutes 480`, empty
`:person-cap-overrides` and `:organization-aliases`. Overrides use stable
account IDs, not names or credential IDs. Aliases map normalized organization
strings to vectors of existing project IDs; ambiguous vectors require explicit
project selection. Enabled MCP requires a nonempty `:data-path` outside the
budgets repository. Unknown settings, invalid timezones and noninteger/out of
range caps fail configuration validation. Paths are rechecked by persistence
adapters before writes; config validation alone is not a filesystem guard.

Records use `external_id`, a real ISO local date, positive integer minutes,
exactly one project ID or organization, optional existing task ID, plain text
notes of at most 240 Unicode code points, and optional boolean volunteering.
Canonical records contain the server-resolved owner ID and uppercase IDs.
Validation returns Failjure failures with `:code`, `:field` and `:next-action`.
Complete proposed days, including existing entries, must not exceed 1440
minutes. Identity mapping rejects duplicate dotted workbook names before any
credential can be provisioned.

## Allocation and capacity contract

`allocate-records` receives complete canonical proposed records, including the
existing draft. It groups by owner/date and project/task before proportionally
sharing the eligible budget. It uses integer quotients/remainders, awarding the
remaining minutes by descending remainder then canonical project/task. Explicit
volunteering receives zero paid minutes. `:vol-minutes` includes both explicit
volunteering and eligible minutes above the cap. Assignment and day totals
conserve recorded minutes; record splitting and order have no effect. Original
records and notes remain in the ledger, separate from these assignment totals.

Each result contains an immutable `daily-work/v1` policy snapshot and SHA-256
hash. The hash covers normalized effective caps, account overrides, timezone,
organization aliases and algorithm version; map order, equivalent alias spelling
and storage location do not change it. Callers compare the current hash before
accepting a preview. These pure functions do not implement publication or modify
past published evidence.

`month-capacity` selects the owner and ISO YYYY-MM month and counts every
nonzero project/task/tag combination across the full month. Paid (empty tag)
and `VOL` occupy separate columns. It returns the exact assignments and minutes,
required columns, a seven-column limit and `:exportable?`; overflow is retained
and must block downstream rendering/publication before mutation. Consolidation
requires an explicit owner decision, never an allocation rule.

DDD diagnostic: 8/10 at this milestone. The model has clear names, explicit
boundaries, a small aggregate, behavioral validation, an identified core domain
and adapter translation contracts, with consistent domain language. The
domain-events row and the depth point for aggregate-enforced persistence
invariants await the ledger implementation; there is no cross-aggregate workflow
implemented here. Rich allocation behavior and consistent domain language earn
the other two depth points.
