# E01 independent review

Reviewed application candidate `4dcfa1e8210247ea3112d8e81c2ec8ed9a08483a` against verified dev `337858d8197d9228d80a03dfcc7d3aef654b4e04`. Subsequent evidence-only changes do not change application source.

## Standards

Reviewed `git diff 337858d8197d9228d80a03dfcc7d3aef654b4e04...4dcfa1e8210247ea3112d8e81c2ec8ed9a08483a` and the corresponding log: one commit, `4dcfa1e feat(expenses): preserve split intent and add weights and adjustments`. Both refs resolve; the diff is nonempty (19 files). Same standards/reference set as A01, using this branch's current `Agents.md`.

**Material hard documented-standard violations: none.**

The new modes follow `Agents.md`'s Strategy map and `SplitContext` factories, retain shared create/update validation and deterministic remainder behavior, and preserve integer-cent shares. Weight multiplication/sums and adjustment sums use `BigInteger`; valid allocations remain bounded by the expense total. Membership/duplicate checks precede strategy execution, and invalid writes remain outside mutation of expense/participant rows.

Original inputs, explicit zeros and zero final shares persist separately from allocations. Legacy percentages degrade to exact shares without inventing original ratios; equal subsets remain recoverable. V11's nullable column accommodates old writers, and the documented restriction on rolling back new enum values is candid. The coordinated V10-before-V11 rollout is an integration gate, not a missing-branch violation.

The editor reuses cached normalized inputs for totals, validation and submission; signed adjustments alone allow negatives, weights require positive safe whole units, and amount caps remain enforced. Original-input restoration does not introduce other members. Existing session/group/load ownership remains intact. Updated labels expose units and member identity, and the segmented controls retain pressed semantics.

Tests use HTTP/reread and rendered-HTTP outcomes for intent preservation, explicit zeros, atomic rejection, cap-scale arithmetic, legacy fallback and payer-excluded remainder ordering. SQL only establishes the damaged/old-writer fixture. These tests were inspected, not run by this reviewer.

**Material judgment-only smell findings: none.** All twelve supplied smells were considered. Client/server validation reflects distinct trust boundaries; the small strategy additions and persisted input map serve current requirements rather than speculative extension points.

E01: **0 material hard findings; 0 material judgment-only findings.**

## Spec

**0 findings.** The implementation matches E01's “Persist typed original inputs plus final shares” and “adversarial weights/adjustments overflow or invalid totals reject atomically.” Original EQUAL sets and custom inputs survive edits; zero-valued participants remain represented. Percentage round-trips use original basis points, including the tiny 50/50 case; amount changes recompute from that intent. WEIGHTS uses bounded positive integers and BigInteger arithmetic; ADJUSTMENTS uses bounded signed cents over the deterministic EQUAL allocation, rejects nonzero sums and negative final shares, and preserves the existing payer/Java UUID remainder rule.

Legacy PERCENTAGE migration and nullable old-writer fallback return exact shares rather than invented ratios. The migration/evidence document accurately limits mixed-version support: V10 precedes V11, and old binaries cannot read new enum rows after new-mode writes. Existing authorization, group serialization, inactive-history guards and draft ownership remain. No E02 identity/versioning, multi-payer, currency or F02 projection implementation is introduced.

Review pinned to `git diff 337858d8197d9228d80a03dfcc7d3aef654b4e04...4dcfa1e8210247ea3112d8e81c2ec8ed9a08483a`; corresponding log is the single `4dcfa1e feat(expenses): preserve split intent and add weights and adjustments`. Both refs resolve and diff is nonempty. Source/test review only; no tests executed and independent populated migration rehearsal remains the parent gate.
