# Cursor options — final design supplement

Prepared **16 October 2026**, after the main audit, visual directions and provisional roadmap, following the user’s priority instruction. These are options to review, not a change to the Angular app.

Try the [interactive four-option cursor study](../../concepts/ui-redesign/cursors.html). It works locally/offline and contains original SVG pointers and animated DOM companions. Move inside a panel, hover/click its sample button and select its text. Replay buttons also show the visual movement on devices without a mouse.

| Option | Appearance and animation | Best match | Recommendation |
|---|---|---|---|
| **Split Tip + twin halo** | A 28px cobalt native SVG arrow, white edge and yellow seam; 28px twin arcs follow with mild damping, open to40px over actions, compress to22px on press and return in160ms | Social Club | **First choice.** Ownable SplitEasy identity, clear hotspot and restrained motion. Use the static branded pointer everywhere appropriate; companion stays near controls, away from money/text. |
| **Orbit ring + satellite** | Mint arrow with peach point; thin ring and a single short satellite arc when hovering an action. 140ms nominal follow and360ms arc | Night Atlas | Best with the direct-balance map. Keep the orbit small and stop after one arc; no perpetual rotating busy signal. |
| **Paper Tab + stamp** | Ink arrow with two coral receipt lines; tiny perforated tab follows, straightens over a group/expense and gives one180ms paper press | Weekend Press | Most distinctive alternative. Matches the paper/edition concept; keep the tab≤28px so it never becomes a floating tooltip over financial data. |
| **Soft Pearl + squash** | Plum SVG arrow with a warm dot; small softly shaded pearl widens on hover and compresses on press | Pocket Objects | Friendliest. Use one pearl and subtle squash; multiple gooey trails would obscure the interface and spend performance on decoration. |

## Compatibility and primary references

- [MDN cursor](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/cursor): URL images have a hotspot and mandatory fallback; image support/size differs by browser. Prefer small static SVG/PNG assets (28px, hotspot3,3 here). Do **not** promise portable native animated SVG/GIF cursors. Animation is the DOM companion; the native custom tip stays accurate even if JS stalls.
- [Pointer media feature](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/@media/pointer) and [PointerEvent](https://developer.mozilla.org/en-US/docs/Web/API/PointerEvent): gate enhancement on `(hover:hover) and (pointer:fine)`, actual mouse events and current modality. Touch uses normal controls and no follower. Text editing uses the standard text caret. Resize/drag/disabled states keep their appropriate semantics.
- [Motion Cursor](https://motion.dev/cursor): useful creative reference for contextual/magnetic treatment, but the documented component uses `motion-plus/react` and requires Motion+ membership. It is not an Angular drop-in and is **not recommended as a dependency here**. No private/premium source was copied.
- [GSAP quickTo](https://gsap.com/docs/v3/GSAP/gsap.quickTo()/): primary reference for efficiently retargeting repeated pointer updates. Only relevant if GSAP is selected as the motion engine later; this plan does not add it merely for the cursor.
- [Codrops custom cursors](https://tympanus.net/Tutorials/CustomCursors/): visual reference for a precise point plus a contextual companion. Inspiration only; none of its code/assets is copied and its particular demo license is not assumed transferable.

## Angular implementation constraints if one is selected

Use original native SVG assets and a tiny standalone `CursorCompanionComponent`, not a cursor package. Render the companion with `pointer-events:none` and `aria-hidden=true`, outside scrolling/transformed containers. Native custom tip remains visible; never depend on a delayed JavaScript follower as the only click point.

One passive pointer handler coalesces writes through one requestAnimationFrame outside Angular. Stop the RAF when settled; no idle frame loop. Limit custom cursor code to≤3kB gzip, no WebGL/shader dependency and no blend-mode over money. Keep final state independent of animation promises. Use the same motion preference service, session teardown and DestroyRef cleanup as the rest of the app.

Reduced motion removes the animated companion but keeps a static branded native tip where supported. Forced colors, unsupported custom image cursors or an explicit user disable fall back to system semantics. Keyboard focus rings remain the visual equivalent; no cursor-only instructions. Hide follower on text entry, pointer leave/cancel, tab blur, hidden document and touch/pen. Cancel every observer, RAF and animation on teardown, session change or preference change.

Before product integration verify hotspots at100/200% zoom, light/dark contrast, multiple monitors/browser boundaries, text selection/caret, financial input precision, touch/trackpad/keyboard switching, custom pointer image fallback, OS reduce motion and forced colors. Cursor rendering can differ on iPadOS, so no identical cross-device claim is made.

**Choice to carry forward:** Split Tip with Social Club; Paper Tab if Weekend Press is selected. Keep pointer selection separate from approving the entire redesign implementation.

## Follow-up: trailing pointers in the designs (16 October 2026)

The user requested a trail and reported no cursor in the actual demos. The four original cursor samples were scoped to the cursor study page. Each of the four full design pages now mounts a shared canvas overlay and palette-matched SVG arrow, with three choices: **Silk Ribbon** (default/recommended), **Comet Trail**, and **Echo Rings**. The toolbar's Preview trail button animates a short sample. A System pointer option is also available. Palette-matched trails run above native dialogs using a manual popover; the overlay is pointer-transparent and does not hide a native tip if animation stops.

The animation uses one requestAnimationFrame chain, caps canvas density at 2×, fades to rest after movement, and clears on text entry, blur, visibility loss and page exit. Reduced motion, forced colors and coarse-only pointers use a system cursor. Browser checks confirmed each selectable style, the live overlay in all four expense dialogs, and immediate reduced-motion disablement. `screenshots/trail-{ribbon,comet,echo}.png` and `preview-interactions.json` record the check. Try the controls on a mouse; a still screenshot cannot convey the full trail.
