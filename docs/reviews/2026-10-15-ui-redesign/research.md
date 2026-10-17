# Design and motion research

Status: proposal research, 15 October 2026. Technical recommendations use primary documentation. Creative adaptations below are our design judgments, not claims about a reference product’s implementation.

## Skills actually inspected and used

| Source | Provenance / use | Decision |
|---|---|---|
| [Superpowers brainstorming](https://github.com/obra/superpowers) and local `brainstorming/SKILL.md` | Installed 6.4.2. Architectural brief, alternatives, written design, self-review and review gate. | This request explicitly asks for a complete audit/design/plan now, so both spec and **provisional** roadmap are delivered together. Product implementation remains gated on review. No commit despite skill default, because user forbids it. |
| [Find Skills](https://skills.sh/) | Installed skill, leaderboard and source inspection. | Discovery, not evidence that a skill guarantees quality. No global install. |
| [Anthropic frontend-design](https://github.com/anthropics/skills/blob/main/skills/frontend-design/SKILL.md) | Official repository, Apache-2.0; leaderboard showed ~991k installs and repository ~181k stars at inspection. | Initial art-direction guidance: specific product content, intentional typography, visual critique. Existing skill/source content was read rather than importing a component kit. |
| **[Taste Skill](https://www.tasteskill.dev/)** → [redesign-existing-projects](https://github.com/Leonxlnx/taste-skill/blob/f624f37a8c650b9cc550253104925efe4c8de246/skills/redesign-skill/SKILL.md) | User requested. Pinned source revision `f624f37a8c650b9cc550253104925efe4c8de246`, MIT. Full redesign skill read. | Main Taste workflow used: source scan, state diagnosis, stack preservation, premium hierarchy and browser critique. Its default v2 explicitly excludes dashboards; its GPT variant mandates React/GSAP/AIDA/randomized marketing architecture, unsuitable here. Those variants were inspected, not applied. Reject inertia scrolling, random imagery and unbounded effects for this financial task. |
| **[UI UX Pro Max](https://uupm.cc/)** → [actual SKILL.md](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill/blob/56ea9cf174ed86786187daa9de0e6247cb0747e4/.claude/skills/ui-ux-pro-max/SKILL.md) | User requested. Pinned revision `56ea9cf174ed86786187daa9de0e6247cb0747e4`, MIT repository. Skill, web quick-reference, app-rule scope and local stdlib search scripts inspected. | Actual local searches executed for design system, Angular lifecycle, modal focus, scroll motion, typography. Use relevant guidance after fit checks; do not blindly paste generated system. |

UI UX Pro Max query findings:

- `expense splitting social finance --design-system --variance 8 --motion 9 --density 7`: finance palette but Brutalism and marketing demo layout; rejected as an overall system. One permitted narrower retry, `personal finance dashboard --variance 7 --motion 8 --density 8`, suggested Glassmorphism and handwritten Caveat/Quicksand. Again unsuitable for legible daily money tasks. No generated MASTER was persisted as authority.
- `keyboard focus modal --domain ux`: visible focus and obscured-focus rules; used with WAI’s actual dialog pattern, because the result was not itself a complete focus-trap recipe.
- `scroll reveal stagger --domain gsap`: headline-only text choreography and small scroll reveals; adapted to Motion/DOM without installing GSAP.
- `playful geometric --domain typography`: childlike Fredoka/Nunito; rejected. One narrower `geometric modern` retry returned Outfit/Work Sans; compared to Space Grotesk/DM Sans, with the latter chosen for tighter numeric and ticket composition. This choice is art direction, not a claimed database match.
- `motion lifecycle cleanup --stack angular`: zero results; one narrower `DestroyRef` retry also zero. **No verified database match**. Lifecycle recommendations below come from Angular’s primary documentation instead.

Raw query outputs are retained under `research/` with decision annotations in this document. Neither source website receives private project or account data. Fetched skills ran locally from `/private/tmp`, without global installation or repository hooks.

## Visual references and specific contributions

| Reference | Contribution to this proposal | Boundary / assets |
|---|---|---|
| [Family](https://family.co/) | Fresh browser observation: friendly financial presentation, clear action hierarchy, dimensional product objects and transition continuity. Adapt the sense of tactility to group passes and settlement receipts. | No crypto feature, wallet UI, mascot, screenshot or proprietary illustration is copied. New artwork is simple original code-native geometry. |
| [Linear](https://linear.app/) | Inspected current page/product presentation: compact hierarchy and concentration on the active object. Adapt action consistency and fast transitions to settings/ledger views. | Do not reproduce its dark SaaS aesthetic or proprietary components. No asset reuse. |
| [Taste Skill examples and redesign guidance](https://www.tasteskill.dev/) | Composition variety, a subject-derived visual device and checking real screenshots rather than trusting generated code. | The “split” in SplitEasy becomes the repeated seam in passes, shares and receipts. The motif is original. |
| [Airbnb release](https://news.airbnb.com/airbnb-2026-summer-release) | Secondary brand/product-context reference for treating a trip as a recognisable object rather than a generic row. | Page text research only; no claim of fresh interaction testing and no asset copying. |

## Angular-compatible motion decisions

| Tool / primary source | Verified capability / license | Recommendation |
|---|---|---|
| [Motion JavaScript quick start](https://motion.dev/docs/quick-start), [animate](https://motion.dev/docs/animate), [MIT source](https://github.com/motiondivision/motion/blob/main/LICENSE.md) | DOM/HTML/SVG animation. Mini versus hybrid builds; hybrid adds independent transforms, sequences and numeric values. npm metadata on this run: `motion` **14.3.0**, MIT, unpacked 809,291 bytes. Docs describe 3.2kb mini/22kb hybrid; those are publisher figures, **not measured SplitEasy transfer sizes**. | Add one exact dependency `motion@14.3.0` only in an approved implementation phase; import DOM APIs from `motion`, never `motion/react`. Prefer CSS for simple feedback and native enter/leave. Measure actual production build cost. |
| [React Motion](https://motion.dev/docs/react) | React components/hooks and React rendering lifecycle. | React’s `motion.div`, `AnimatePresence`, `layoutId`, `useSpring` do not directly work in Angular. No React migration or wrapper dependency. |
| [Motion scroll](https://motion.dev/docs/scroll), [inView](https://motion.dev/docs/inview), [hover](https://motion.dev/docs/hover) | Scroll progress versus viewport triggers are distinct APIs. inView is IntersectionObserver-based. Hover filters emulated touch hover. APIs return controls/unsubscribe functions. | Use once-only reveals, one decorative scroll-linked seam and limited spring feedback. Keep content visible if JS/setup fails. Call returned disposers and stop/cancel owned animations. |
| [Angular enter/leave](https://angular.dev/guide/animations) | Compiler-supported enter/leave CSS classes and integration callbacks. Baseline Angular 21 compiler contains the matching APIs; verify its types before implementation. | CSS + `animate.enter`/`animate.leave`; do not add deprecated `@angular/animations` or providers. Leave callbacks cannot own business mutations. |
| [Angular route View Transitions](https://angular.dev/guide/routing/route-transition-animations) | Router integrates native View Transitions; docs mark integration developer preview and describe browser fallback. | Optional progressive enhancement behind a support guard. One group pass/title pair per navigation; ordinary routing remains correct. Skip auth/session boundaries, reduced motion, rapid navigation and unsupported browsers. No dependency on Motion+ layout/view features. |
| [GSAP ScrollTrigger](https://gsap.com/docs/v3/Plugins/ScrollTrigger/), [context](https://gsap.com/docs/v3/GSAP/gsap.context()/), [matchMedia](https://gsap.com/docs/v3/GSAP/gsap.matchMedia()/) | Scrub, pin, timelines, scoped revert and media-query lifecycle. npm metadata: **3.15.0**, unpacked 6,258,071 bytes; unpacked package is not runtime bundle cost. | Capable alternative if a later interactive explainer needs complex pinning. Do not ship it alongside Motion for the planned daily app; the chosen choreography does not need two engines. |
| [GSAP price](https://gsap.com/pricing/), [license](https://gsap.com/community/standard-license/) | Free use under a custom license, with restrictions on competing visual animation builders; **not MIT**. | No paid plugin purchase needed for assessed use; retain notices if later adopted and recheck terms at implementation. |
| [CSS scroll timelines](https://developer.mozilla.org/en-US/docs/Web/CSS/Guides/Scroll-driven_animations), [WAAPI](https://developer.mozilla.org/en-US/docs/Web/API/Web_Animations_API) | Native timelines and animation controls; feature availability differs. | Feature-test decorative scroll effect. Static visible fallback. Native WAAPI is sufficient for this throwaway concept; production Motion handles orchestration. |
| [Angular lifecycle](https://angular.dev/guide/components/lifecycle) | afterNextRender, DestroyRef, render phases. | Element-scoped setup after render; batched reads/writes; disconnect on destroy and account change. No global query-selector animation over financial content. |

No dependency was added to the Angular project in this phase. Versions are research snapshots and must be rechecked if implementation starts substantially later. Version-specific Angular documentation hosts were unavailable through web retrieval; local installed Angular 21 types are the compatibility backstop.

## Accessibility, performance and assets

- [WAI modal pattern](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/): name, focus inside, contained navigation and launcher restoration inform the shell redesign. Existing CDK provides a focus trap without Material presentation.
- [WCAG animation from interactions](https://www.w3.org/WAI/WCAG22/Understanding/animation-from-interactions.html): a useful stricter design target for disabling nonessential motion; this is an AAA criterion, not a claim that every motion rule is AA.
- [web.dev animation guide](https://web.dev/articles/animations-guide): favour transform/opacity, inspect compositing and avoid layout-driven scroll work. Small disclosure height measurement is an explicit exception, measured once rather than continuously.
- [Web Vitals](https://web.dev/articles/vitals): use LCP/INP/CLS goals and record laboratory methodology separately from field data.
- [Space Grotesk OFL](https://github.com/google/fonts/blob/main/ofl/spacegrotesk/OFL.txt), [DM Sans OFL](https://github.com/google/fonts/blob/main/ofl/dmsans/OFL.txt): self-hostable open fonts; license texts accompany locally saved concept fonts. Prototype TTFs are **not** the production font budget; implementation uses subset WOFF2 variable fonts, retains licenses, preloads only critical files and measures font swap.
- Icons/illustration: original small SVGs using a shared stroke. If an icon library becomes useful, choose individual MIT-licensed Phosphor SVGs and retain attribution/license; no React icon package. No stock-photo service, paid image, copied mascot or remote hero video is required.

Cursor options are a later supplement in [cursor-options.md](cursor-options.md), added after the main design deliverables as requested.
