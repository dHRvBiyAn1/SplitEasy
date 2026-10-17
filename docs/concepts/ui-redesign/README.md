# Four SplitEasy design proposals

Started 15 October; expanded to four standalone designs on **16 October 2026**, after the user asked for three more different styles. These are editable design studies with synthetic data, not rewritten Angular screens.

Open [the comparison gallery](gallery.html). To serve locally from the repository root:

```sh
python3 -m http.server 4317 --bind 127.0.0.1 --directory docs
```

Then open `http://127.0.0.1:4317/concepts/ui-redesign/gallery.html`. The files also work directly from disk. Fonts and assets are local; no credentials, ledger writes or analytics calls are made.

| Proposal | Distinct layout / typography | Palette / geometry | Signature motion | Best fit / cost |
|---|---|---|---|---|
| [Social Club](index.html) | Top navigation; balance ticket scene; illustrated group passes; Space Grotesk + DM Sans | Cobalt `#2446E8`, blue-grey `#EDF1F4`, butter `#F3DF75`; notched receipts and rounded passes | Split seam, pass-to-ledger handoff, allocation redistribution | Earlier recommendation. Strong character and daily financial clarity. Most developed: overview, group, auth, profile and modal compositions; light/dark preview. |
| [Night Atlas](atlas.html) | Sidebar console; large readout and direct-balance map; compact group rows; IBM Plex Sans + IBM Plex Mono | Forest `#111F1D`, mint `#C2EACB`, peach `#F5B89B`; small radii and exact alignment | Directional line draw, object arrival; map/list toggle | Most concentrated/analytical. Requires restraint with diagram labels and a plain list equivalent. Map is only existing per-viewer pairwise data. |
| [Weekend Press](press.html) | Large masthead; yellow/coral spread; full-width groups as typographic posters, no card grid; Bricolage Grotesque + DM Sans | Paper `#F5F1E7`, yellow `#F0E980`, coral `#EFA58D`, ink `#29262A`; crisp edges and perforated paper | Receipt unfolds, typographic underline, short paper rotation | **Boldest visual departure.** Memorable and shareable. More scroll area; page headers must become compact in daily subflows. |
| [Pocket Objects](objects.html) | Centered capsule navigation; group collection left, central balance object, people right; Nunito Sans + DM Sans | Lilac `#EEE8F4`, plum `#664273`, sage and blush; soft volume and varied round forms | Object settling, physical press, small orbiting avatar arrival | Friendliest/playful. Must avoid over-soft contrast and excessive depth. No WebGL or 3D runtime is needed. |

## What the previews demonstrate

All four include sample expense entry with three split-mode layouts, group detail/breakdown, empty-group state, create group/member forms, settlement list→confirm→sample success, and profile presentation. Social Club has fuller page studies; the other three use modal studies to demonstrate their component language. These are **composition prototypes**: share totals and some controls are illustrative and are not a replacement for production validation, routing or security logic. All “save” actions state that nothing was persisted. No full backend integration is claimed.

Social Club’s prior palette-only alternatives have been replaced in navigation by actual standalone layouts. The four-way gallery is the review entry point. The user has now selected **Pocket Objects**, with **Seafoam & Petrol as default**, Silk Ribbon and liquid-glass hover/press. The other directions remain reference alternatives.

## Visual evidence and boundaries

Screenshots live in [screenshots/](screenshots/). The recommended study was inspected in both themes, the alternatives in the theme shown. Desktop 1440×1000 and mobile390×844 captures are saved; 320px and768px overflow measurements are recorded in `responsive-checks.json`. A 7px masthead overflow in Weekend Press at320px was reproduced and corrected by reducing only its smallest-width masthead size. No global overflow clipping hides the defect.

The preview Reduce motion control was exercised along with named native dialogs, initial focus, labelled percentage fields, Escape dismissal and sample success. OS-level reduced-motion emulation, real-device Safari, full contrast/accessibility certification and production animation performance remain implementation verification work. These studies use finite native CSS/WAAPI animation rather than installing the proposed Angular runtime dependency.

## Files and reuse

- `index.html`, `study.css`, `study.js`: Social Club.
- `atlas.html`, `press.html`, `objects.html`, `alternatives.css`, `alternatives.js`: three separate compositions with shared **prototype behavior only**.
- `gallery.html`: comparison entry point.
- `cursors.html`, `cursors.js`, `cursors.css`: later cursor supplement.
- `assets/`: locally hosted fonts, OFL licenses and original SVG cursor drawings. Prototype TTF files are not the proposed production WOFF2/font budget.

Read the [full current-UI audit](../../reviews/2026-10-15-ui-redesign/audit.md), [design specification](../../superpowers/specs/2026-10-15-ui-redesign-design.md), [implementation roadmap](../../superpowers/plans/2026-10-16-ui-redesign.md) and [research](../../reviews/2026-10-15-ui-redesign/research.md). No product source, package dependency, API or schema has changed.

## Twelve palettes, two themes, three trails — 16 October follow-up

Open the [color comparison board](colors.html) to see three colors for each layout. Its **Light / Dark** controls switch all twelve thumbnails; every thumbnail opens that exact palette and theme in the interactive study.

| Design | Option 1 | Option 2 | Option 3 |
|---|---|---|---|
| Social Club | Cobalt & Butter | Juniper & Apricot | Merlot & Rose |
| Night Atlas | Forest & Mint | Midnight & Ice | Aubergine & Sand |
| Weekend Press | Citrus & Coral | Ink & Sky | Cherry & Cream |
| Pocket Objects | Lilac & Plum | Peach & Cocoa | Seafoam & Petrol |

Every study now has **Palette**, **Light mode / Dark mode**, and **Pointer** controls in its toolbar. Profile theme selection also changes the alternative demos. The selected palette/theme/pointer are included in the URL; theme and pointer choices also survive navigation in the same tab via a prototype-only sessionStorage key. They do not touch application preferences.

The original cursor supplement was restricted to its sample boxes, which is why the cursor was absent from the actual layouts. The new **Silk Ribbon**, **Comet Trail**, and **Echo Rings** run directly over every design, including native dialogs. Silk Ribbon is the default: a thin tapered two-color trail with a precise SVG arrow. Comet uses diminishing dots; Echo Rings follows with three fine outlines. **Preview trail** makes their motion visible without having to chase the pointer. The overlay never receives clicks, rests when idle, and clears on blur, page exit or text entry. Touch, forced colors and reduced motion keep a system cursor. The original four cursor studies remain available as earlier explorations.

Shared source: `palettes.js` holds the twelve paired schemes; `preview-options.css` maps illustration and surface roles; `preview-options.js` handles controls and the canvas trail without adding dependencies. Assets have a version query so earlier cached demo scripts do not hide the changes.

Verification: 24 desktop and 24 narrow-screen palette/theme checks, plus eight final 320/768px layout checks with no horizontal overflow; actual theme controls and dark expense dialogs in all four designs; three trail selections, dialog overlay and reduced-motion fallback. A rendered-text contrast check covered 1,872 text instances in the 24 desktop views. Low-contrast Peach/Seafoam captions and avatar text were corrected. These computed checks do not certify all states, gradients, real-device performance or accessibility. JSON records and updated light/dark screenshots are in `screenshots/`.


## Selected Pocket Objects refinement — 16 October 2026

Open [Pocket Objects](objects.html). **Seafoam & Petrol** is the default for a fresh preference set. The header’s **Appearance** button and **Profile** expose the three color themes (Seafoam/Petrol, Lilac/Plum, Peach/Cocoa) and separate Light/Dark choices. Switching colors keeps the mode. Isolated `spliteasy.study.objects.*` localStorage keys remember appearance for this demo; they do not replace application/account settings.

Silk Ribbon is fixed for this selected direction. Touch adds a brief bloom and ribbon, fading after release; each touch starts cleanly at its own position. Passive touch listeners preserve native scrolling and pinch; multiple touches/cancellation, text entry, blur/visibility loss and reduced motion clear decoration. **Preview touch** in Appearance provides a finite rehearsal with a mouse or keyboard. This confirms the rendering path; real iOS/Android touch behavior and performance remain product/device validation work.

At widths through1024px, the floating glass navigation shows four SVG-and-label controls: Overview, Groups, Expense, Profile. All use one active-capsule style. Opening Expense/Profile selects that item; closing restores the previous destination. Each mobile target is at least44px high. Desktop keeps the main navigation. Native hash anchors are demonstration destinations, not additional product routes.

`pocket.css` and `pocket.js` add the selected layout controls and a restrained glass treatment on header/dock and bounded group/people objects. Hover tracks a soft highlight and lifts cards by3px; touch uses a press sheen. Exact financial values stay on steady readable surfaces. Motion reduction stops the trail and moving glass highlights; system colors get solid surfaces. No glass package or animation dependency was added.

Evidence: `pocket-responsive-checks.json` covers30 theme/mode/width combinations (320/390/768/1024/1440), all with no horizontal overflow, four dock icons, one current item and valid tap heights where visible. Appearance choices, persistence, dialog selection restoration, touch rehearsal and reduced motion were exercised through the browser. Updated selected-direction captures and contrast records are in `screenshots/`.
