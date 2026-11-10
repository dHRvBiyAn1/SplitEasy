# Domain documentation

This application is a single domain context shared by the Java backend and Angular frontend. Their separate build systems do not require separate glossaries.

Before exploring a domain change, read root `GLOSSARY.md` if present, then relevant `docs/adr/` decisions if present. If either is absent, continue with the source and current spec. Create a glossary only when a term is resolved; create an ADR only for a material, difficult-to-reverse trade-off that would surprise a future maintainer.

Use the glossary's terms in interfaces, test names, tickets and explanations. Surface a conflict with a recorded ADR before changing the decision. Keep implementation details out of the glossary.

The current alignment design distinguishes active membership, historical financial participation, net balances, direct obligations and simplified obligations. These are proposed design terms, not a claim that all proposed models are already implemented. See `docs/superpowers/specs/2026-10-19-splitwise-alignment-design.md` for the proposal; `Agents.md` and source describe shipped behavior.
