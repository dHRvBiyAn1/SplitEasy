# SplitEasy — session handoff

This handoff summarizes work from the earlier chat. Check current GitHub state and repository instructions before making changes; the other environment's local files may differ.

## Current priority: complete security audit

The user requested a full security audit of the complete `dev` branch with [Cloudflare's security-audit skill](https://github.com/cloudflare/security-audit-skill). Install the skill in this environment, read its `SKILL.md`, and follow its full workflow.

The earlier audit did **not** complete: the previous session had no sub-agent delegation tool required by the skill. Only preliminary read-only source searches were performed; they did not establish that the project is free of vulnerabilities. The earlier local-only report was `/workspace/security-audits/SplitEasy/run-1/REPORT.md`, which may not be accessible from this environment.

Use the current verified `dev` commit as the audit target, check for any accessible prior audit evidence, and record coverage and validation limitations honestly. Follow the skill's sandbox requirements and do not probe production or real user data.

## Latest recorded project state

- PR [#107](https://github.com/dHRvBiyAn1/SplitEasy/pull/107) merged G01 core: stable financial participants, guests, verified email claims, and one-use group invitation links.
- PR [#108](https://github.com/dHRvBiyAn1/SplitEasy/pull/108) merged G02.1 into `dev` at `ebd10a23ebdad4d2ba05264f8425f1994d992c0e`. It adds a private saved-friends directory and authorized group balances. PR CI passed before merge; the post-merge CI was still running at the last check. Verify the latest remote state and checks.
- G02 is not complete. Its next planned serial slice is authorized non-group expense CRUD (G02.2), followed by personal debt/payment views and complete friend history. The outstanding security audit is the more recent user request.
- Phone/SMS invitations remain gated on selecting a verified delivery channel.
- The user selected Google Workspace for eventual production SMTP but deferred its configuration.
- The user confirmed all existing historical amounts are USD.

## Plan and project files

- [Main Splitwise alignment roadmap](../superpowers/plans/2026-10-19-splitwise-alignment.md)
- [Foundation plan](../superpowers/plans/2026-10-19-splitwise-alignment-foundation.md)
- [Backlog JSON](../superpowers/plans/2026-10-19-splitwise-alignment-backlog.json)
- [Backlog CSV](../superpowers/plans/2026-10-19-splitwise-alignment-backlog.csv)
- [G01 participant identity and invitations](../superpowers/plans/2026-10-26-alignment-g01.md)
- [G02.1 friends directory](../superpowers/plans/2026-10-26-alignment-g02.md)
- [G02.1 verification](../reviews/2026-10-27-alignment/g02-friends-directory.md)
- [Project instructions](../../Agents.md)

The `docs/superpowers/` directory name is historical and does not by itself determine the active workflow.

## User preferences

- Use **both Superpowers and Matt Pocock skills** as appropriate. This later user instruction overrides the earlier instruction in repository documents to use Matt Pocock instead of Superpowers.
- Keep Ponytail's simplicity guidance. The user also requested installation of [Superpowers](https://github.com/obra/superpowers), [Karpathy guidelines](https://github.com/multica-ai/andrej-karpathy-skills), and [Addy Osmani agent-skills](https://github.com/addyosmani/agent-skills). Verify availability in this environment.
- At most three active worker threads. Do not use Astra; Sol models must use at most high reasoning.
- Development branches start from verified `dev`, and implementation PRs target `dev`.
- Preserve financial identities, amounts, original split inputs, history, and retry receipts.
- The user asked for GPT-6.1 Terra with high reasoning for the next session; model selection is controlled by the client.
