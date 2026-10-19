# F04 independent review

Base: `d0ea1b7a7711d9f4350c964a3c5dbbdc88c4a9ca`. Reviewed candidate: `eb96d0a3d089adb13d1da28a5a5abb5b45cbfb70`. Independent Matt Pocock Standards and Spec axes ran separately. Subsequent commits retain evidence/project-memory only; application source is unchanged.

## Standards

# Standards review: final F03/F04 candidates

Reviewed each worktree with `git diff d0ea1b7a7711d9f4350c964a3c5dbbdc88c4a9ca...HEAD` and `git log d0ea1b7a7711d9f4350c964a3c5dbbdc88c4a9ca..HEAD --oneline`. The fixed point resolves in both worktrees; both diffs are nonempty. F03 HEAD is `5a368cb9575a7164af4f6fcad40eae56e3d9308d` (1e41124, 5a368cb). F04 HEAD is `eb96d0a3d089adb13d1da28a5a5abb5b45cbfb70` (769c498, 7549546, eb96d0a).

Sources: each candidate’s current `Agents.md`, `README.md`, project engineering workflow, `.agents/skills/code-review/SKILL.md`, and the full Fowler smell baseline. No CODING_STANDARDS.md or CONTRIBUTING.md was found. Tool-enforced formatting is excluded.

## F03

**No remaining documented-standard violations or material judgment-only smell findings.**

The previous P2 deletion-feedback finding is resolved: `frontend/src/app/groups/group-detail.component.ts:250` associates the failure with `expenseId`; `frontend/src/app/groups/group-detail.component.html:162` renders its named alert on that expense outside the expansion. `toggleExpand` no longer clears deletion feedback. This satisfies `Agents.md`’s “Financial read/mutation errors stay actionable” rule. Added rendered/HTTP regressions cover delayed failure after collapse, switching to another expense, and successful removal/refresh (`group-detail.component.spec.ts:81`).

Repeated modal session guards and pending cleanup implement the documented account-session and pending-feedback contracts; extracting a general lifecycle abstraction would add complexity without a demonstrated need.

## F04

**No documented-standard violations or material judgment-only smell findings.**

The contract changes reuse existing OpenAPI schemas and clearly distinguish current behavior, proposed work, and companion F01 lifecycle behavior. The F01 dependency is explicit in `docs/reviews/2026-10-19-alignment/f04-contract.md:3`; integrate F04 with or after F01. This is a dependency condition, not a new finding. The historical `docs/superpowers/` directory follows the engineering workflow’s explicit storage rule.

Read-only source review; no application tests were rerun. Recorded F03 213 frontend/207 backend, build/format/browser evidence and F04 schema/reference/owned-HTTP evidence were inspected as retained implementation evidence, not fresh reviewer executions.

**Totals: F03 0 hard / 0 judgment-only findings; F04 0 hard / 0 judgment-only findings.**


## Spec

# Spec axis — final F03/F04 candidates

Reviewed independently against the alignment design and foundation Tasks 4–7 using Matt Pocock's local `code-review` skill and `docs/agents/engineering-workflow.md`. No Standards conclusions are incorporated.

Fixed point `d0ea1b7a7711d9f4350c964a3c5dbbdc88c4a9ca` resolves in both worktrees; both three-dot diffs are nonempty. Commit lists checked: F03 `1e41124`, `5a368cb` (HEAD `5a368cb9575a7164af4f6fcad40eae56e3d9308d`); F04 `769c498`, `7549546`, `eb96d0a` (HEAD `eb96d0a3d089adb13d1da28a5a5abb5b45cbfb70`).

## F03

**No meaningful Spec findings.** Read-only identity, explicit device-preference copy, current-expense retry, group/edit-owned member retry with typed fields preserved, shared pending-close behavior, forced session teardown, settlement identity, live totals and financial dates implement Tasks 4–6 without introducing future account/currency APIs.

The original P2 deletion-feedback issue is fixed: `frontend/src/app/groups/group-detail.component.html:162` renders the expense-ID-owned alert outside expanded detail; `group-detail.component.ts:248` retains the originating ID. Collapse, switching to another expense, and successful refresh have rendered regressions (`group-detail.component.spec.ts:81`, `:102`, `:125`). All four pending dialogs distinguish user closure from forced session closure; requests cancel at session/destruction boundaries. Payment operation reuse remains intact, including uncertain failure/reopen coverage.

## F04

**No meaningful Spec findings.** OAS 3.1 nullable/reference defaults, required non-null primitive split value, auth429/Retry-After, keyed 201 replay/409 changed intent, narrow persistence-conflict limits, greedy/display semantics and differing error bodies match Task 7. Companion historical-participant/lifecycle/isolation claims agree with inspected F01 source; F04 must integrate with or after F01/F03. Owner-confirmed USD does not claim C01 migration or conversion shipped.

Independently reran the retained verifier: duplicate-key YAML parsing, **128 internal references**, official OAS 3.1 schema, and 81-row backlog checks passed; F04 `git diff --check` passed. Reviewed retained baseline HTTP observations and F03 test/browser evidence; no application tests or browser rehearsal were rerun by this reviewer. Reported frontend213/backend207/build/Chromium1280/390 results remain implementation evidence, with browser APIs explicitly intercepted.

Spec totals: **F03 0; F04 0**. No code edits, commits, pushes, PRs or merges performed.


Both axes reported zero remaining findings for this package. Reviewers inspected source/evidence and did not claim new test executions.
