# F02 independent review

Reviewed candidate `ff58959f06c6f4a5aa0d3c80e4768892a211b797` against verified dev `337858d8197d9228d80a03dfcc7d3aef654b4e04`. Both independent axes report zero remaining findings; later evidence/metadata changes do not change application source.

## Standards

## Standards — F02

Reviewed `git diff 337858d8197d9228d80a03dfcc7d3aef654b4e04...ff58959f06c6f4a5aa0d3c80e4768892a211b797` and matching log: `fcfd5fd` implementation plus `ff58959` metadata. Both refs resolve; the diff is nonempty (35 files).

Sources: current `Agents.md`, `README.md`, `docs/agents/engineering-workflow.md`, `docs/agents/domain.md`, and the previously read local Matt Pocock code-review/TDD/codebase-design skills and companion references. No tracked CONTRIBUTING, CODING_STANDARDS or GLOSSARY was found.

**Material hard documented-standard violations: none.**

`ObligationProjectionService` centralizes mode selection; its pure calculator is shared with the preview without a dependency cycle. Pairwise signs, dashboard/People/settlement rows and departure checks flow from that projection. Raw obligations retain their original aggregate/read contract, historical identities remain included, and membership checks precede group existence checks. Read boundaries retain `REPEATABLE_READ`; guarded writes join their group-locked `READ_COMMITTED` transaction, following `Agents.md` snapshot/serialization rules.

Non-null mode PATCHes lock and reauthorize before projection. Former-participant exposure fails before settings assignment, preserving accompanying rename/type changes atomically. Omitted-mode name/type PATCHes retain dirty-column behavior. Net balances/history remain derived from unchanged rows; matching uses amount then UUID ties and integer cents.

Settings waits for canonical obligations and disables departure on unavailable reads. The settlement modal consumes canonical dashboard rows without a second preview and retains session cancellation, pending-write feedback and payment-operation ownership. Contract descriptions distinguish raw/current obligations and the nullable direct-mode preview warning.

Modified legacy tests explicitly select direct mode where their financial assertions concern raw pairs. Obsolete second-preview tests were replaced by rendered HTTP action coverage. Mode-last stale-read cases now run sequentially because the new lock prevents that old ordering; reverse stale non-mode cases remain, supplemented by real PostgreSQL mode/departure/payment/expense ordering tests. Expected outcomes are asserted through public reads; probes only coordinate transactions. Root metadata updates authorization/batch state rather than claiming the whole roadmap shipped.

**Material judgment-only smell findings: none.** All twelve baseline smells were considered. The shared calculator/projection remove substantive duplication; compatibility wrappers do not warrant a material Middle Man finding.

No tests were executed by this reviewer; recorded suite/browser results remain separately verified gates.

Standards: **0 material hard findings; 0 material judgment-only findings.**

## Spec

## F02 — Spec

**0 findings** in `git diff 337858d8197d9228d80a03dfcc7d3aef654b4e04...ff58959f06c6f4a5aa0d3c80e4768892a211b797`. Both refs resolve and the diff is nonempty. Corresponding log: `fcfd5fd fix: align obligations and departure rules with group mode`; `ff58959 docs: record second alignment batch authorization and rollout`.

The implementation matches design §2: “dashboard, People, group balances, settlement actions, and leave eligibility consume that same projected result.” `PairwiseBalanceService` delegates to the selected projection; its existing dashboard callers therefore share the same counterparties, signs, headline totals and settlement rows. Settings reads current-mode obligations, removal uses the same projection, and settlement records the canonical dashboard row without an independent preview. Simplified A→B→C becomes A→C and its payment clears simplified surfaces while preserving the raw cycle. Direct cycles keep incoming and outgoing actions, explanatory copy and open-balance labels rather than a false settled result.

The net-preview warning satisfies: “A net-derived settlement preview in direct mode must clearly say that it can leave offsetting direct obligations.” `/balances/obligations` remains raw; the additive current-obligations endpoint uses selected mode. UUID tie-breaking is shared by preview and canonical matching. Complete financial principals remain in the underlying balances; historical counterparties are resolved without granting access.

Mode changes take the existing group-row lock under READ COMMITTED and recheck membership after waiting. Target-mode former-member exposure rejects with repair guidance before any name/type/mode mutation, matching: “Switching modes cannot create an actionable debt involving a former member.” Simplified zero-net intermediaries can leave; creators remain protected. F01 inactive-history guards, payment operation identities and recorded financial data remain unchanged.

Public financial reads retain REPEATABLE READ at their owning boundaries, including dashboard, pairwise, preview and projection. Changed snapshot probes follow the new raw aggregate queries; new HTTP tests exercise S4, direct cycles, historical exposure, UUID ties and both mode/departure orders. Existing snapshot tests now explicitly use direct mode; the simplified path still joins the established balance snapshot boundary. No currency, multi-payer, schema or backfill expansion was found.

Static source/test review only; no tests executed. Packaged HTTP/browser rehearsal and combined integration verification remain parent-owned gates.
