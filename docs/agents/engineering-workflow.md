# Engineering skills

The user selected [Matt Pocock's skills](https://github.com/mattpocock/skills) instead of Superpowers on 19 October 2026. Apply this selection to the Splitwise alignment work and subsequent implementation in this session. Ponytail remains the simplicity guide; REA remains available for evidence-based investigation.

## Installed source

Project-local copies are in `.agents/skills/`, pinned to upstream commit `49dd158d1076134a641b33efb035946536778336`. The original skill directories and companion references are unchanged. `mattpocock-source.json` records file hashes; `MATTPOCOCK-LICENSE` retains the MIT license. These are local skill files, not a globally registered plugin. The repository already ignores `.agents/`; retain that policy.

In a new workspace where those ignored files are absent, fetch the pinned repository and copy the listed skill directories, including their companion files, to `.agents/skills/`; retain the license and provenance. Read the files directly if this harness has not yet refreshed its skill catalogue. Do not claim a slash command or installed-plugin registration is available merely because the files exist.

## Select skills by task

| Task | Skill file |
| --- | --- |
| Regression-first feature/bugfix work | `.agents/skills/tdd/SKILL.md` |
| Bug diagnosis or performance regression | `.agents/skills/diagnosing-bugs/SKILL.md` |
| Standards and spec review | `.agents/skills/code-review/SKILL.md` |
| PR description | `.agents/skills/pr/SKILL.md` |
| Interface/test seam decisions | `.agents/skills/codebase-design/SKILL.md` |
| Resolving terminology or recording a justified ADR | `.agents/skills/domain-modeling/SKILL.md` |
| Updating agent-facing instructions/plans | `.agents/skills/writing-for-agents/SKILL.md` |

Also installed: user-invoked `implement`, `implement-spec`, and `setup-matt-pocock-skills`. Preserve their user-invoked status; use their orchestration when the user selects that entry point. This setup change does not invoke either implementation workflow.

Project setup uses choices already established in this session: [GitHub issue tracker](issue-tracker.md), [single-context domain docs](domain.md), and existing planning documents. The triage skill is not installed; no triage vocabulary or remote labels were introduced.

## Project rules that override skill defaults

- **Branches:** separate issue/package branches start from latest verified `origin/dev`; PRs target `dev`. Dependencies integrate into `dev` before dependent branches start. This overrides `implement-spec`'s default of basing all work on a separate integration branch.
- **Concurrency:** at most three active worker threads, including implementers, researchers, mergers and reviewers. Schedule the two independent `code-review` axes only when two slots are free. No Astra; Sol reasoning at most high. Keep the plan's task-specific model selections.
- **Authorization:** the user requested implementation of the plan on 19 October 2026; the first two batches were completed and merged, and the user subsequently approved E02/A02/H06 through reviewed PRs to dev. Follow the user's current commit/PR/merge authorization; a skill's default commit or publish step does not independently authorize external writes. Do not republish the existing spec as a GitHub issue just to change workflows.
- **Testing:** the first-batch plan proposes HTTP, rendered-UI and browser test seams. Before writing new tests, confirm the chosen seams through the user's implementation instruction or an explicit seam selection. If that instruction does not establish them, ask only for the missing seam decision. Within a task, run one behavioral test → red → minimal change → green before the next test. Scenario lists are coverage requirements, not a direction to write a bulk test suite before implementation.
- **Financial evidence:** test outcomes at authorized HTTP/read/UI interfaces. SQL may set up old damaged fixtures; synchronization probes may coordinate races. Internal query counts and database inspection are supplementary diagnostics, not the primary assertion that a user workflow works.
- **Review:** pin the branch's recorded base SHA before implementation; pass it and the spec path to `code-review`. Keep Standards and Spec findings separate. Do not ask again for a fixed point already recorded in the task handoff.
- **PRs:** use the skill's Summary/Evidence/Merge Danger sections and append the repository-required Testing section. Scale the summary visual to the actual change. Record exact commands/results and material migration or rollback limits.
- **Verification:** use the repository's required checks before a code PR and its release gates before release claims. The standalone testing/verification obligations remain even though Superpowers is no longer the workflow.

## Existing plan storage

The existing storage location for the current planning artifacts remains `docs/superpowers/`; the directory name is historical and does not select the active workflow. The alignment plan headers now point here and carry no Superpowers skill requirement. Keep existing links working rather than moving documents merely to rename their parent directory.

No global plugin configuration, application dependency, source behavior, GitHub issue, remote label, commit, push or deployment was changed by this skill switch.
