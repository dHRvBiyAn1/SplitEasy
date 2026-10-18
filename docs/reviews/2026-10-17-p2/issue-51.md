# Issue #51: expanded expense refresh verification

**Result:** Current `dev` source already satisfies the reported behavior. Group reload unsubscribes an in-flight expense-detail request, clears the detail cache, and collapses the expanded row before refreshed summaries are loaded. No product logic change was needed.

The regression test in `frontend/src/app/groups/group-presentation.spec.ts` renders the group expense list, starts an old detail request, and simulates the successful edit invalidation through the shared dashboard refresh. It verifies that the old request is canceled, the detail row is collapsed while the updated `$12.03` summary is shown, and re-expanding fetches current detail with the saved payer, total, and `$7.03` / `$5.00` shares.

## Validation

- `cd frontend && npm test -- --watch=false` — 111 tests passed.
- `cd frontend && npx prettier --check "src/**/*.{ts,html,scss,json}"` — passed.
- `cd frontend && npm run build -- --configuration production` — passed.
- `cd backend && JAVA_HOME=/workspace/toolchains/jdk-21.0.12.1+1 ./mvnw -s /workspace/maven-proxy-settings.xml -B spotless:check verify` — passed; 138 tests, zero failures/errors.

This verifies the client invalidation and rendering path with Angular's HTTP test backend. It does not claim a live production edit or browser/API exercise.
