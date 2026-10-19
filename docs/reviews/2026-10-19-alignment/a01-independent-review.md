# A01 independent review

The first candidate `b04fc6e88c8497a4352039c91e06bb0ad4bd4801` passed Standards review; Spec review found a stale read-only email display. The fix candidate `3ed7d1c3433192bbefad69c634484e4fb604382c` renders the accepted server email in both modes and adds a differing-email regression. No email mutation was added. Backend source is identical to the234-test/packaged-JAR candidate. Both final axes report zero remaining findings.

## Standards

## Standards — A01 review follow-up

Reviewed `git diff b04fc6e88c8497a4352039c91e06bb0ad4bd4801...3ed7d1c3433192bbefad69c634484e4fb604382c` and its log: one commit, `3ed7d1c fix: display authoritative read-only profile email`. Both refs resolve; the diff is nonempty. Backend is identical (`git diff --quiet ... -- backend` exited 0).

Applied the previously inspected repository standards, Matt Pocock references and all twelve judgment-only smell heuristics.

**Material hard documented-standard violations: none.** Both profile modes now display the accepted server email, preserving the documented read-only email contract and unchanged stored authentication identity. No service, mutation contract, session ownership or migration behavior was changed.

The rendered regression deliberately differs cached/server email, checks both display modes and absence of an email input, inspects the actual submitted PATCH, and confirms auth email remains unchanged. Import coverage now checks failed-write retention, explicit retry, owner isolation and destruction/recreation with the same singleton. It meaningfully tests whether import is offered again without inventing cache state or adding an unneeded implementation fix. Evidence records the non-reproduction candidly.

**Material judgment-only smell findings: none.**

No tests were rerun by this reviewer; recorded 226-test/build results and root HTTP/browser verification remain separately owned evidence.

Standards: **0 material hard findings; 0 material judgment-only findings.**

## Spec

## A01 remediation — Spec

**Prior P2 resolved; 0 remaining findings.** Reviewed `git diff b04fc6e88c8497a4352039c91e06bb0ad4bd4801...3ed7d1c3433192bbefad69c634484e4fb604382c`; both refs resolve, diff is nonempty, and the corresponding log contains `3ed7d1c fix: display authoritative read-only profile email`.

Both view and edit templates now prefer the accepted `/api/me` email, satisfying design §3: “Token name/email claims are display hints, not authoritative fresh account records.” Cached auth email is only a fallback before an accepted profile exists. Email remains read-only, the PATCH excludes it, and ID/token/auth identity stay unchanged.

The added rendered regression supplies different cached/server email values, checks both modes and the serialized PATCH, and checks unchanged cached auth email. The retained import regression also exercises failed import, explicit retry, owner-local deletion and component recreation under the same singleton. Its success path accounts for `auth.user()` invalidating the legacy preference computed; no speculative cache fix is needed.

Static source/test review only; no tests executed. Parent-owned final packaged HTTP, migration and browser verification remains a separate gate.
