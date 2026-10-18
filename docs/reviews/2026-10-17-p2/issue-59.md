# Issue #59 — financial control accessibility

Verified locally on 2026-10-18 against dev baseline `f68d818`.

The Pocket Objects redesign already gives the debt switch its “Simplify debts” name and `aria-checked`, and payer/category buttons their `aria-pressed` state. The remaining gaps were payer names shortened to first names, duplicate display-name split labels, and exact amount labels without a unit.

The existing expense template now names payer and participant controls with the full display name and email, retaining “You” for the current member. Exact and percentage labels explicitly name dollars and percent. No financial calculation, input constraint, request ownership, dependency or visual component changed.

## Testing

- Regression first: the two new rendered Angular cases failed against the original payer names. Cases cover three members sharing a first name, two sharing the same complete display name, payer/category selection changes, exact/percentage units, mismatched totals disabling Save, and valid requests preserving cents/basis points and the chosen payer/category.
- Real browser: system Chromium 154.0.8037.57, headless, local Angular server on port 4259, intercepted synthetic API responses only. Playwright used computed role/name locators; CDP `Accessibility.getFullAXTree` independently verified payer/category `pressed` properties. No production data or API was accessed.
- Settings: `switch "Simplify debts"` checked → unchecked → checked through two successful PATCH responses; role/name stayed available throughout.
- Expense group: Alex Rivera (`me@example.test`), Alex Chen (`alex.chen@example.test`) and Alex Chen (`another.alex@example.test`). Three distinct payer names and checked participant names resolved by role. Exact spinbuttons resolved as full identity + “exact amount in dollars”; percentage spinbuttons as full identity + “share in percent”.
- Switching payer selected only the second Alex Chen; switching category exposed Groceries pressed and Food & drink unpressed. Browser AX state agreed. Both split modes disabled Save before the final share and enabled it when balanced. Intercepted POSTs retained amount 1234 cents, correct payer and Groceries, exact shares 411/411/412 cents or percentage shares 3000/3000/4000 basis points.

This is Chromium accessibility-tree evidence, not a screen-reader or physical-device test. Shared request-ownership (#47) and normalization (#52) fixes remain separate.

Verification commands (logs retained outside the checkout under `/workspace/issue-59-*.log`):

```sh
cd frontend
npm test -- --watch=false
npx prettier --check 'src/**/*.{ts,html,scss,json}'
npm run build -- --configuration production
cd ../backend
JAVA_HOME=/workspace/toolchains/jdk-21.0.12.1+1 ./mvnw -s /workspace/maven-proxy-settings.xml -B spotless:check verify
cd ..
python3 -m unittest discover -s deploy/tests
```

Frontend: 112 tests passed. Prettier and production build passed. Deployment checks: 12 passed. The isolated browser harness and raw AX/payload evidence are retained at `/workspace/issue-59-browser.py` and `/workspace/issue-59-browser-evidence.json` for this session; the assertions and identities are summarized above for durable review.

Backend: 138 tests passed, no failures/errors/skips; Spotless check and Maven verify succeeded. Independent parent review cleared the final implementation. `git diff --check` passed.

## CI formatting follow-up

PR #90 run 39097517030 stopped at the full Prettier check because the final test-selector edit followed the earlier formatting pass. Reproduced the exact check on commit `480e135`: only `new-expense-modal.component.spec.ts` failed. Running Prettier on that file reflows the `find` callback without changing behavior. The full source-tree formatting check was then rerun successfully against the final content.

Follow-up results: full frontend suite 112/112, production build, exact full-source Prettier and `git diff --check` pass. Backend/browser tests were not repeated for the whitespace-only test change; their prior results remain applicable.
