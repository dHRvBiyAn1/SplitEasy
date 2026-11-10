# Issue tracker: GitHub

Repository: `dHRvBiyAn1/SplitEasy`, confirmed by the existing origin and user instructions. Use the configured GitHub connector or authenticated `gh` CLI. Existing issues are GitHub issues; the alignment roadmap's F01/A01/etc. identifiers are local planning IDs, not published issue numbers.

- Fetch an issue with `gh issue view <number> --repo dHRvBiyAn1/SplitEasy --json number,title,body,labels,comments` or the equivalent connected read tool.
- Read PRs separately; GitHub shares issue/PR number space. Implementation PRs target `dev`; release PRs target `main` from `dev`.
- Publish specs/tickets or modify remote issue state only when authorized. Use exact structured tool arguments or `--body-file` for multiline content. This skill-selection request creates no issues, labels, comments, PRs or closures.
- Until publication is requested, use the alignment Markdown plan and CSV/JSON dependency map as the proposed work graph. Preserve blocking edges if later converting it to native GitHub dependencies.
- PRs as a request surface: **no**. An unrelated external PR is not automatically a triage request.
- Existing priority label: `priority:P2`. The triage skill is not installed; no new canonical triage labels are assumed or created.

When an invoked skill says “fetch the relevant ticket,” read the referenced GitHub issue or explicitly named local planning package. When it says “publish,” perform the write only within the current user authorization.
