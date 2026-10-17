# SplitEasy Pocket Objects — selected design direction

**Status: Pocket Objects selected16 October2026 and implemented in Angular locally17 October2026 following the explicit implementation request.** Prepared15 October and expanded through the four studies on `feat/ui-redesign` at `597fc93`. Earlier proposal detail below is design history. Selected scope, deliberate exclusions and actual verification are recorded in the [implementation ledger](../../reviews/2026-10-15-ui-redesign/implementation/ledger.md) and [verification report](../../reviews/2026-10-15-ui-redesign/implementation/verification.md). Backend interfaces, schema and production data are unchanged. The user subsequently authorized commit/push, successful branch CI, then a PR to dev. No merge or deployment is authorized.

## Brief and success

The user wants a complete, visually striking redesign with ambitious animation across the whole expense-splitting app. They explicitly request web research, Taste Skill and UI UX Pro Max, and later ask for creative animated cursor options after the main deliverables. Existing Evenly styling is context, not a constraint against a new art direction. Keep Angular 21, the current product and ledger/security behavior. No React migration or unrelated hosted site.

Assumed audience: friends splitting dinners/trips and households tracking shared costs, often on a phone or while multitasking. Success means a memorable identity, a cohesive set of screens/states, faster comprehension of who owes whom and delightful feedback that never delays the financial task. Account changes, unsupported animation APIs, reduced motion and keyboard use must remain first-class paths.

The [current audit](../../reviews/2026-10-15-ui-redesign/audit.md) inventories actual screens, gaps and evidence. [Research](../../reviews/2026-10-15-ui-redesign/research.md) records skills, citations, licenses and rejected suggestions. [Four-way editable gallery](../../concepts/ui-redesign/gallery.html) is illustrative, with synthetic data and no API traffic. It is not a functional Angular implementation or a full visual acceptance test.

## Four visual directions

| Direction | Composition / type / surfaces | Motion personality | Trade-off |
|---|---|---|---|
| Social Club — earlier recommendation | Split-circle brand mark; pale-blue desk; cobalt balance scene; group passes with a visible seam; Space Grotesk + DM Sans; curved outer objects, crisp aligned ledger rows | Pass unfolds into group, allocation seam moves between people, cash receipt receives a short confirmation stamp | Most product-specific: homes, dinners and trips become recognizable shared objects. Strong personality without requiring photos or 3D rendering. |
| Night Atlas | Deep green map-like space, mint ink, sparse connection lines, compact group rows, IBM Plex Sans + IBM Plex Mono, less rounding and more measured spacing | Paths draw to explain counterparties; one shared destination travels across routes | More analytical and subdued; risks suggesting a relationship graph the current API does not model. Use only existing pairwise data, not fabricated connections. |
| Weekend Press | Paper/yellow/coral edition, a large masthead, full-width poster group rows; Bricolage Grotesque + DM Sans | Perforated receipt unfolds, short paper rotation and underlines reveal | Boldest departure; uses more vertical space. Compact subpage headers are essential for daily use. |
| **Pocket Objects — selected** | Seafoam/petrol default; optional lilac/plum and peach/cocoa surfaces, soft keepsake shapes, central balance object flanked by groups and people; Nunito Sans + DM Sans, larger round geometry | Small object springs, ticket pull-out, soft press response | Most playful; easiest to overdo. Costs more compositing and can resemble a toy unless money stays exceptionally disciplined. |

The user selected **Pocket Objects with Seafoam & Petrol as the default**, **Silk Ribbon** as the mouse treatment, a corresponding touch response, and an icon-and-label mobile/tablet navigation bar. Each of the three color themes must have an independent Light/Dark mode. Preserve these choices across visits. The other three art directions remain research alternatives; do not combine their layouts into the selected system. Use the **SplitEasy** name throughout.

The selected demo now includes a user-facing Appearance panel from the header and Profile. The static study saves only isolated browser appearance preferences; this is not a product/account-preference migration. Add restrained liquid-glass highlights to the header, navigation dock and selected group/people objects, with solid readable money surfaces.

## System and tokens

### Palette

All final values live in `frontend/src/app/styles/_tokens.scss`; page styles use semantic variables. Retain `.dc-amount` as the financial semantic authority, with new values behind it. A plain expense/payment total never inherits owed/owing colors.

| Role / proposed token | Light | Dark | Use |
|---|---|---|---|
| Canvas `--bg` | `#E2EFEA` | `#15282A` | Quiet seafoam desk |
| Surface `--surface` | `#F7FDF9` | `#21383C` | Receipt, forms, menus |
| Surface inset `--surface2` | `#CCE3DC` | `#314E50` | Selected neutral area |
| Main ink `--ink` | `#23464A` | `#E9FAF6` | Primary content |
| Secondary ink `--ink2` | `#476260` | `#B0D0C9` | Supporting text, never faint labels |
| Accent `--accent` | `#286970` | `#A2D9D2` | Primary action, selected state, identity |
| On accent `--on-accent` | `#F7FDF9` | `#1E4244` | Text/icons on accent |
| Warm illustration `--illustration-warm` | `#EDD495` | `#EDD495` | Split motif; not financial status |
| Owed `--pos-strong` | `#17614C` | `#8BE0BE` | Owed amount, plus text |
| Owing `--neg-strong` | `#9B2948` | `#FFA8BB` | Owing amount, minus text |
| Control border `--control-line` | `#78869E` | `#8494B0` | Input/control boundary requiring contrast |
| Separator `--line` | `#ACC9C5` | `#527B7C` | Decorative row division, not sole control affordance |

Use 4.5:1 normal-text and 3:1 large-text/non-text targets as applicable. Measure every actual composed pairing, including colored illustrations, focus, disabled controls, overlays and selection. Decorative pass tints may be lighter; meaningful text remains strong ink. Error is a separate semantic role even if its palette resembles owing. Never rely on red/green alone: include “You owe,” “Owes you,” signs or status text. Zero reads “Settled” / “All square,” with no plus or minus sign.

Dark mode is a designed set, not inversion. Preserve System/Light/Dark and current ThemeService precedence. Also synchronize native `color-scheme` with the effective theme, so date/select controls match an explicit override. Prefer a quiet 120ms surface crossfade when theme changes; no radial full-screen wipe around financial content.

### Type, space and geometry

- Display: **Nunito Sans**, 700/800/900. Body, controls and money: **DM Sans**, 400/500/600/700. Brand and large summary use Nunito Sans. Self-host subset WOFF2 with OFL files and matching fallbacks; two families, no remote runtime font dependency.
- Type steps: body 16/24px; supporting text 14/20; small metadata 12/18; row title 16/22; section title 24/29; page heading 40/42 desktop, 32/35 mobile; expressive auth display 64/65 desktop, 40/43 mobile. Sentence case; no forest of tracked uppercase labels.
- Money: tabular numerals, explicit cents, grouping separators, stable sign/currency placement. Hero 56/60 normally, 32/38 for long values; rows 16/22, group net 24/28. Never abbreviate a payable amount into “6.6B” or clip digits. A full amount may wrap as a whole onto its own line; digits/sign/currency remain a coherent string. If a 15+ digit aggregate still exceeds available width, allow a labelled, keyboard-scrollable amount region rather than document overflow or lost precision.
- Spacing tokens: 4, 8, 12, 16, 24, 32, 48, 64px. Standard form gap 20/24 can be represented by an explicit token rather than component magic values. Outer gutters 16 at 320–374, 20 at 375–719, 32 at 720–1199 and 48 above. Content max width 1344px. Avoid full-screen dead space between daily tasks.
- Geometry: rounded group objects around26px; central balance uses the Pocket Objects soft silhouette; controls around20px; avatars circular. Inner ledger uses flush rows and purposeful separators, not cards inside cards. A notched seam appears only where parts genuinely belong together.
- One original SVG illustration family for the five group types and eight categories, standard 1.8px stroke at 24px. Abstract original type art for group passes; no photo-upload feature is implied. Avatars use existing initials/color hashing with accessible text contrast.
- Shadows: one subtle tinted elevation for passes, one stronger modal layer. Liquid glass is confined to navigation and bounded group/people objects, with a solid fallback. No blur on ledger rows, balance numerals or a page-wide compositing layer. Hover adds an edge highlight and a short pointer-position sheen; touch adds press feedback. Reduced motion removes the moving sheen/lift; forced colors uses system surfaces.

### Layout and primitives

The desktop shell becomes a clear top bar: SplitEasy/home, Overview, Groups disclosure, Create group, profile. The Groups tray replaces the always-visible sidebar with a named, collapsible list/drawer carrying current group names and balances; keyboard navigation and active state remain. Navigation changes are visual, not new product destinations: `/groups` stays a redirect, and Overview maps to `/dashboard`. The concept’s “Lisbon group” and “Sign in concept” buttons are **study controls**, not proposed production navigation.

Above1024px, use the compact top bar and group tray. At mobile/tablet widths through1024px, keep brand, Appearance and Profile in the header and show a floating glass dock: Overview, Groups, Expense, Profile. Each has a labelled SVG icon and the same active capsule treatment; Expense is highlighted only while its composer is open, then the previous destination is restored. Groups opens the tray, rather than inventing a new route. A group-level sticky action area can replace the generic Add button contextually but must not duplicate primary controls. Account/sign-out remains reachable at every width. Safe-area insets, virtual keyboard and 200%/400% zoom may expand/collapse chrome into ordinary document flow.

Reuse current primitives rather than introduce a UI framework: `.dc-btn-*`, `.dc-field`, `.dc-avatar`, `.dc-amount` evolve in `_ui.scss`. Add focused reusable units only where shared: `MoneyComponent`, `StatusPanelComponent`, improved `ModalShellComponent`, `MotionPreferenceService`, a small reveal directive and `GroupPassComponent` if it removes substantial duplication. Use the already installed Angular CDK for focus management. Material visual components are not introduced. No generic custom animation framework.

## Screen and state design

| Surface | Proposed composition and interactions | Required states / behavior retained |
|---|---|---|
| Login/register | Full-bleed split composition, a concise story and animated receipt being split among initials. Clearly labelled form. Auth switch preserves visual continuity. Password visibility button and persistent hints. | Invalid fields after blur/submit, linked summary if several invalid, incorrect login, duplicate register, slow submit and network failure. Correct autocomplete, paste and password manager support. Preserve session reset on success. No fake social sign-in, password reset or invented invitation. |
| Shell / navigation | One brand, fast top navigation and accessible group tray. Profile is an actual labelled destination on mobile. Compact tray rows carry full accessible group names and signed balances. | Loading groups, empty groups, stale refresh notice, very long names, many groups, rail/drawer open/close and focus restoration. Closing tray cannot obscure the active focus. Existing business destinations remain reachable. |
| Dashboard | A central soft balance object with exact net and separate owed/owing pockets; group collection to the left, People to the right, activity below. Mobile puts the overall balance first, followed by groups and People. A split seam/ribbon visually separates owed and owing while labels and exact values stay authoritative. | Initial skeleton sized to final layout; stale data stays during refresh with small status; no-groups onboarding with Create group; settled zero; no activity; no people; retry panel. API payload is still one cached dashboard response. No invented time-series chart from its capped feed. |
| Group passes | Type art, group name, members once, one dominant personal net and total-spent context. Entire pass is one semantic link; secondary membership action belongs in detail/settings. | Positive/negative/zero, 100-char group name, large totals, 1–many members. Shorten only visible name with full accessible text/expansion, never money. |
| Group detail | Header holds group identity and actions. Readable balance summary with expandable counterparties. Month-grouped ledger, date, category, payer/total, clearly separate viewer impact. Expenses unfold into a receipt. Add member is discoverable beside group identity or through members/settings. | Loading, forbidden, server failure/retry, zero expenses with Add expense, failed detail load, edit/delete failure. Re-fetch expanded content after edit; a deleted/changed group cannot leave stale ledger UI. Maintain per-group pairwise semantics. |
| Expense breakdown | Payer total, participant shares and split type, visually aligned decimal columns. Edit and delete explicit and labelled. Proposed custom destructive dialog replaces browser confirm while retaining its explicit irreversible warning. | Only one active expanded row, rapid switching, loading/error retry, long member names, participants with zero share. Preserve full-replace PUT and hard delete. No undo button unless a real backend restore behavior is separately added. |
| Expense create/edit | Large desktop sheet (max 880px): receipt facts left, split allocation right. Mobile full-height sheet with sections and persistent running total/action footer. Compact group/payer selects, expandable category picker, date and amount prominent. Edit label and current scope always visible. | Group/member/detail loading, reads fail, invalid/over-cap/negative/NaN/blank amount, missing description, no selected members, exact/percentage mismatch, saving/error/success. Retain entered values after failure. Existing equal, UNEQUAL and basis-point requests unchanged. |
| Split allocation | Equal: membership checkboxes plus per-person preview. Exact: member-specific money fields. Percent: member-specific percentage fields and derived money preview. Horizontal stacked share bar explains allocation; the list is always the accessible source. | Sum uses integer request values, cap and overflow rules from backend. Payer absorbs remainder; preview uses same deterministic order and labels a rounding cent when relevant. Do not imply original percentages can always be recovered from persisted cents; editing keeps current behavior or explicitly resets to exact shares with clear text rather than fabricating precision. Changing modes preserves per-mode drafts while the modal remains open, if included in implementation review. |
| New group | Small composed form: name, five types as accessible radios, existing-account email chips. Type illustration gives identity. | Empty/blank/max-length validation, invalid/duplicate email, creating, adding members, partial success. Group may already exist after creation succeeds; report failed member emails and allow retry only those. Never show success while silently dropping an email. No sending invitations. |
| Add member | Compact sheet with group name, labelled email input, current people and result inline. | Loading/read failure/retry, unknown/existing member/server error, adding/success. Existing-account requirement clear. Success updates group/dashboard without leaking old-session callbacks. |
| Settlement list | Rows clearly say payer→payee, group, exact amount and direct/simplified mode. One group’s setting controls its rows. Dashboard scope can mix modes. | Loading, empty, mixed settings, simplification failure with direct fallback notice, scope changed. Use “Suggested transfers,” not a proof of global fewest transactions. No toggle independent of group settings. |
| Settlement confirm/success | A legible editable receipt with payer, payee, group, cash-recording language, partial amount and explicit Record payment. After successful response, update state immediately, announce confirmation, show brief stamp. | Back/cancel, zero/blank/cap validation, partial and overpayment, saving/retry, double click suppressed. Overpayment remains permitted; a sign flip is explained, not prevented. Recording is not an actual money transfer. A failed request never celebrates. |
| Activity/history | Redesign existing merged feed with expense/payment distinctions and dates. Payment amount is neutral. | Empty/loading/failure, long descriptions and capped recent items. Optional follow-on group payment history uses existing `PaymentService.listPayments(groupId)`; it is **not required** for this base redesign and must be approved as added scope. Concept marks its sample panel as proposed. |
| Group settings/members | Calm sectioned sheet-like page with inline rename, member rows/balances, named simplify switch and separated danger area. Expand inline confirmation without moving the target. | Creator badge, settled/unsettled reason, remove/leave 409, rename and switch loading/failure, confirmations/back. Keep creator-only group delete and all membership guards. Any member may edit settings/expenses under current authz. |
| Profile/appearance | Compact personal page, inline editing, avatar preview, explicit local-storage notice, first-visit wording. Theme previews side by side; motion reduction setting below theme. | View/edit/cancel/save, long name/email, absent phone. Local preferences stay local; currency choice does not convert ledger currencies. Do not invent backend persistence or signup date. Existing global preference ownership issue is separately tracked, not claimed fixed by visual work. |

## Shared feedback and accessibility

Initial loading reserves the likely layout after 150ms, avoiding flashing a skeleton for a fast response. A status region announces “Loading dashboard”; reduced motion uses a static skeleton. Refresh retains trusted current-session data, marks it refreshing, and clears instantly on session reset. After error, offer a real retry action; if state-changing response is uncertain, refresh authoritative data before asking the user to resubmit.

Forms use visible labels, fieldset/legend for modes, member-specific accessible names, `aria-invalid`, described error/hint IDs and semantic disabled state. Financial running totals update visually per edit, but polite announcements are debounced to avoid reading every keystroke. Multiple submit errors focus a linked summary; a single error focuses its field. A failed mutation retains the draft.

Modal entry immediately establishes a named dialog and focus. CDK focus trap plus managed inert background, body scroll locking, return focus and safe close-on-Escape/backdrop form the shared contract. Focus targets remain within view above sticky footer and virtual keyboard. Large expense sheet initially focuses its heading or first field without auto-scrolling the document. Only the topmost dialog responds to Escape. Dirty-form dismissal prompts are a proposed interaction improvement: a discard check is needed only when values changed, not for an untouched sheet. Session reset bypasses all exit/dirty checks and discards the previous user’s state synchronously.

Status and success never move keyboard focus unexpectedly or rely solely on ephemeral toast. A saved receipt confirmation is also reflected in updated list/activity and an accessible status. No celebratory sound or vibration. Destructive action uses a focused explicit confirmation with the item/group named, clear scope and a safe cancel action; no aesthetic animation replaces the actual confirmation.

## Motion storyboard

Primary rhythm: **split → distribute → reconnect**. A shared object splits into people’s shares, the ledger preserves where it came from, and payment reconnects it to a clear receipt. Animated numbers must never misrepresent current balances: update final text immediately, animate surrounding emphasis only. No slot-machine money count, perpetual floating dashboard, scroll hijacking, cursor-dependent action or hiding content until a timeline finishes.

Tokens: press 90ms, release/hover 150–180ms, micro 180ms, modal enter 260ms/exit 170ms, route 220ms, feature reveal 360ms, one-time auth/overview signature 640ms. UI ease `cubic-bezier(.22,1,.36,1)`; on-screen relocation `.65,0,.35,1`; restrained spring stiffness 340/damping 30/mass 1 for object feedback only. No bounce on field text or payable amounts. Stagger 35ms, max cumulative delay 140ms. Scroll entrance does not replay every time scrolling reverses.

| Trigger / scene | Animation and timing | Exit, interruption and ownership | Touch / keyboard | Reduced motion |
|---|---|---|---|---|
| First auth visit | Receipt’s seam separates into three labelled shares, 640ms; headline remains readable | Once per page mount; stop on navigation/hidden tab; no infinite loop | Passive visual; form usable immediately | Static final receipt |
| Login→dashboard | Surface handoff 180ms; no shared screenshot across identities | Clear old auth state first; never animate prior user pixels | Focus main heading immediately | Immediate route |
| Dashboard initial response | Balance ticket unfolds/rotates ≤2°, 480ms; first 4 group passes settle with 35ms stagger | Max 620ms overall; no replay on refresh; killed on session/route change | No interaction blocked; no touch tilt | Static final layout |
| Scroll into activity | Seam draws, header/first rows 8px reveal in 280ms; only first entrance | inView disposer; user focus forces final visibility; no reverse-hide | Same content and order for every input | Full content, no travel |
| Scroll-linked decoration | One pass illustration moves 8px or seam draws across section progress | Only while in viewport; no pinned app content, no scroll interception | Ordinary native scroll; no dragging | Static illustration |
| Pass hover / focus | Lift ≤3px and shadow, 180ms; subtle inner illustration shift | Retarget from current state; stop on pointerleave; no width/height | Focus gets border/highlight, no required tilt; touch uses press | Color/outline only |
| Press control | 0.98 scale 90ms, 150ms return | Cancel pointer gesture and restore immediately; native click owns action | Native button/link, Enter/Space as appropriate | Immediate state layer |
| Open Groups tray | Translate 12px + opacity 220ms; group rows 25ms stagger capped100 | Close170ms; inert/focus consistent; rapid open/close replaces animation | Explicit button and Esc, no swipe required | Immediate open/close |
| Group pass→group route | One title/type-object shared transition220ms in supported browsers | One active transition; skip on rapid nav/auth/session; data-ready bounded fallback | Route focus and scroll behavior work independent of visuals | Skip view transition |
| Open expense receipt | Disclosure layout measured once, content fade180ms | Rapid row switching cancels prior measurement/response; latest expense ID wins | Native expanded button + controls target | Instant layout |
| Edit→saved row | Highlight seam180ms, latest summary/detail text immediate | Mutation response is authority; abort visual callback on account change | Polite “Expense saved,” focus row action | Static changed state |
| Modal open | Scrim opacity180ms; panel Y14px/scale .98→1 in260ms | Establish focus/inert before animation; superseding modal cancels | Same named dialog, initial focus, Esc | Instant, no scale/blur |
| Modal close | Panel opacity/Y8px170ms | Disable obsolete panel interactions immediately; never delay unsubscribe/reset; fallback removal timer | Return focus to launcher; removed launcher→main | Immediate teardown |
| Split mode change | Highlight slides150ms; allocation bar redistributes180ms | Latest mode wins; preserve focused inputs and current draft; no stale end handlers | Button/radio semantics; arrow keys if radio pattern | Instant selected state/bar |
| Edit split value | Bar segment transform/clip130ms after integer calculation | No timer controls amount; no animation on exact financial text | Labelled inputs and plain totals; not drag-only | Static final share bar |
| Add/remove email chip | Opacity + 4px180ms; reflow once | Focus next chip/input; cancel on close/session | Named Remove email button | Instant chip |
| Async save | Button text “Saving…” crossfade100ms; lightweight status after150ms | Immediately disable repeat submit; request lifecycle independent of animation | No focus theft, input preserved on failure | Static status |
| Validation failure | Error area appears120ms; no shake | Corrected error disappears with current validation; avoid race-prone delayed clears | Focus summary/field and described error | Immediate error |
| Settlement list→confirm | Counterparty and receipt replace content180ms | Back returns to originating row; no stale prefill after scope/account change | Same labelled flow, stable tab order | Immediate replacement |
| Record payment success | Local stamp rotation−12° settles and six small seam pieces burst ≤420ms | Only after successful response; abort on session/close; all particles removed | Status text, Done accessible; no need to watch | Static check + status |
| Entire group becomes settled | One quiet check/seam closure360ms, only if real before/after zero | No celebration on mere zero initial load; no animation-owned payment write | Same zero amount and settled words | Static settled state |
| Rename / settings switch | Save check180ms, switch thumb150ms | Response controls final state; failure restores authoritative value | Visible labels and keyboard operated native switch | Immediate state |
| Theme change | Background/text transition120ms | Effective OS/user choice wins; suppress during active text entry if flash risk | Ordinary appearance group | Instant theme |
| Profile edit / avatar preview | Panel crossfade150ms; preview updates immediately | Cancel restores draft, no network implication | Same form order; account session clears obsolete view | Instant preview |

The celebration is short and local to the receipt, not a full-screen confetti overlay. A global settled message appears only when both owed and owing are zero, not when their net happens to be zero. A user who owes Noor and is owed by Luca must still see both directions.

## Toolkit, lifecycle and performance

Use **CSS + Angular 21 enter/leave + one pinned `motion@14.3.0` DOM dependency**. Motion handles springs, sequences and viewport/scroll helpers. CSS handles hover/focus/press and common state changes. Native View Transitions via Router are optional progressive enhancement; their developer-preview status and support fallback are explicit. Neither `framer-motion` React components nor `@gsap/react` are compatible drop-ins. GSAP/ScrollTrigger is a researched alternative, not a second runtime in this plan. No Lenis, scroll smoothing, Three.js, canvas particles, Lottie player or chart library is necessary.

Own animation handles at component/directive scope. Set up element-specific effects after rendering; avoid matching selectors across multiple route instances. Use `DestroyRef.onDestroy` for teardown, invoke each Motion observer/gesture/scroll disposer and cancel WAAPI handles. `stop()` may commit styles; `cancel()` may restore initial styles, so specify the correct final static state explicitly. One owner per transform property; use nested wrapper elements for route motion versus hover feedback.

`MotionPreferenceService.reduced` is true if OS requests reduction **or** the user opts for Reduced. CSS media queries and `data-motion='reduced'` cover both. Listen to OS changes; turning reduction on cancels existing decorative effects and renders the final state immediately. No initial flash of hidden content. Media listeners are disposed. Setting is device appearance preference, not ledger/account data.

Session reset has stronger ownership than visual exit. It must synchronously cancel animation handles, observers, cursor followers and pending toasts; clear modal/route identity content and skip any View Transition snapshot. Existing HTTP `takeUntil(session.changes)`, generation counters and protected-401 session version checks remain intact. Do not move state mutation into an `onComplete`, timeout, Promise continuation or animation-end event. A canceled/rejected animation promise is handled without a stale state write.

Performance acceptance budgets, measured on the production build:

- Initial JS/CSS increase ≤35kB gzip versus measured baseline; Motion feature code lazy-loadable, total added motion code ≤30kB gzip. Respect current Angular 500kB warning/1MB error initial and 8/16kB component-style budgets. Publisher package/download sizes are not these measurements.
- Critical font transfer ≤120kB compressed total; total app font transfer ≤180kB. Use minimal WOFF2 subsets with licenses, only critical preloads. Prototype TTFs are exempt and must not be copied into app blindly.
- Lab goal under documented mobile throttling: LCP ≤2.5s, CLS ≤0.1, representative interaction-to-paint p75 ≤200ms where field samples exist. Lab Event Timing and input response are supporting evidence, not a substitute for field INP.
- Pointer/scroll callbacks must not run Angular change detection per frame. One RAF at most; no idle RAF loop. Decorative scripting average <2ms/frame on tested reference device, no animation-induced >50ms long task, aim for 60fps with <5% frames above 32ms in a 10s capture. Measure with Performance panel/trace, not subjective “smoothness.”
- At most one signature scene and two short supporting effects active at once. Only transforms/opacity for continuous effects; avoid moving blur/filter surfaces on mobile. Apply will-change only during motion, remove after. Offscreen/hidden-tab decorative effects stop.

No timing promise replaces real performance measurement. If a signature fails budget, simplify its layers or drop travel rather than weaken the input/ledger guarantees.

## Scope and review gates

Base implementation covers all currently existing screens, modals and states, the new visual/motion system, honest status copy, accessible navigation/dialogs and relevant state regressions discovered while changing those surfaces. It preserves auth/session APIs, all three split types, group permissions, partial/overpayment, hard-delete semantics, current group simplify setting and dashboard aggregation. No DB migration or backend change is required. Existing ledger snapshot/overflow protections remain protected by full backend tests before any PR.

Optional additions requiring separate scope review: full per-group payment-history panel, persistent account-profile endpoint/currency model, new animations preference beyond simple Reduced. The selected Silk Ribbon/touch treatment is now part of the visual brief, rather than an undecided cursor option. The selected Silk Ribbon is now integrated into the Angular app; prototype alternatives remain references.

Review sequence: (1) direction selected: Pocket Objects, Seafoam & Petrol default, three independent color themes plus Light/Dark, Silk Ribbon and corresponding touch feedback; (2) review the updated written spec, including mobile/amount/motion behavior; (3) reconcile and approve the provisional plan and execution method; (4) implementation in this branch, PR to `dev` only when requested, full checks before PR. Promotion to production remains a separate tested dev→main release. No commit/push/deploy is authorized here.

## Self-review

- Every existing route/modal has a row above and an implementation owner in the plan.
- History is explicitly marked as optional, profile storage remains honest, simplified greedy transfers are not called globally optimal, and net zero is not confused with settled.
- Prototype uses synthetic data and simplified illustrative controls; it does not claim backend integration or complete visual coverage.
- Existing token and financial semantics are preserved as architecture while palette/geometry/typography are proposed replacements. No unrelated refactor or framework migration.
- Cursor guidance is intentionally deferred to the final supplement, per the user’s priority instruction.


## Selected pointer and touch behavior

Silk Ribbon keeps a precise palette-matched SVG mouse tip and a short tapered trail. A finger gets a brief bloom and trail only during touch, fading after lift; no synthetic mouse arrow remains on a touch screen. Preserve passive event handling and native scroll/pinch; multiple touches, cancellation, blur/visibility loss and text entry clear decorative state. A new press/preview starts at its own location, without joining the previous mouse position. Forced colors and OS/user reduced motion disable the decoration; financial/task state never depends on animation. Actual Safari/Android touch and performance checks remain release QA; the local implementation report distinguishes automated ownership tests and desktop rehearsal from hardware evidence.
