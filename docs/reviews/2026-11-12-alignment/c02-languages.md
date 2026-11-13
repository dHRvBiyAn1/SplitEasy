# C02 — Hindi, Spanish and French UI (migration V30)

> **All three catalogues (`hi.json`, `es.json`, `fr.json`) are MACHINE-DRAFTED.** No native speaker has
> reviewed them. Native review is a required follow-up before R2 is announced.

## What shipped
- **V30** `users.locale varchar(8) not null default 'en'`; allow-list `en|hi|es|fr` in `UpdateProfileRequest`
  (400 otherwise); returned/patched through `GET/PATCH /api/me`; OpenAPI `ProfileResponse`/`UpdateProfileRequest`.
  (Reserved as V29 originally; the integrator moved it to V30 after H02-A took V29.)
- `core/i18n/`: `I18nService` (`SUPPORTED_LOCALES` is the one list to extend; instant switch; lazy
  `/i18n/<code>.json`; English bundled; per-key fallback to English; `Intl.PluralRules` plural keys
  `key_one/key_other`), `t` pipe, `ldate` pipe, standalone `translate()` for non-injected code.
- 890 keys in `public/i18n/en.json`; every template and UI string in TypeScript routed through it.
  Money, dates and numbers use `Intl` with `en-US`/`hi-IN`/`es`/`fr`; amounts, currency codes and
  minor-unit exponents are untouched. `<html lang>` follows the locale.
- Profile **Language** select (endonyms via `Intl.DisplayNames`); signed-out pages use the browser language,
  the server preference wins after sign-in, session reset returns to the browser default.
- `COMMENT_CREATED` activity event (integrator request) in all four catalogues, with a no-description variant.

## Red → green
- Backend `ProfileIntegrationTest.localeDefaultsToEnglishPersistsAndRejectsUnsupportedLanguages`: failed
  (`No value at JSON path "$.locale"`), passes after V30/entity/DTO/service.
- Catalogue completeness (`i18n.service.spec.ts`): 3 failures while `hi/es/fr.json` were empty, green once filled.
- `profile-language.spec.ts`: failed (no `/i18n/es.json` request / no select), green after ProfileService + Profile control.
- Activity spec for `COMMENT_CREATED`: failed (raw key), green after the key + fallback.
- i18n service spec: interpolation, plurals, per-key English fallback, instant switch, Hindi grouping in `app-money`
  (`INR 1,23,456.78`), session reset, unsupported locale, failed catalogue load.
- All pre-existing English specs stayed green throughout.

## Gates (merged tree incl. dev 1a36974)
- Backend `./mvnw -B clean spotless:check verify`: 460 tests, 0 failures. (Needed `clean`: a stale `target/` held my
  pre-rename V29 file next to H02-A's V29.)
- Frontend Node 22 `ng test --watch=false`: 455/455 in 70 files, stable (see flake fix below).
- `prettier --check src`: clean. `ng build`: succeeds, initial total 621.30 kB (was ~553 kB + 890-key bundled English).

## Deviations and known gaps
- The `t` pipe is **impure** (plan said pure): a pure pipe would not re-run when the locale signal flips.
- Keys were generated from the English text (`screen.firstWords`), not hand-named, because ~890 strings were
  extracted mechanically; they are stable and screen-scoped but not all "semantic". Worth a rename pass if wanted.
- Some messages are concatenated fragments (e.g. a count next to a noun, "claim first") translated per fragment;
  grammar is approximate in places.
- Backend error messages stay English (as planned). Palette names and the brand strings were left as-is.
- Static label lists built in class fields (group types, categories, payment methods) are translated at component
  creation, not live; every screen is recreated on navigation, only Profile's own TS labels would lag a live switch.
- A failed PATCH after choosing a language leaves the UI in the chosen language for the session while the profile
  error is shown.
- **Not verified:** 320px truncation for French/Spanish, real browser/screen-reader checks, native-speaker quality.
  Only rendered jsdom specs were run.

## Agents.md delta
- Progress Log: `- [x] Alignment C02 languages (2026-11-13) — V30 users.locale; runtime i18n (en bundled, hi/es/fr lazy, machine-drafted, native review pending), 890 keys across every template/TS string, locale-aware money/dates (hi-IN grouping), Profile language select, <html lang>, backend allow-list en|hi|es|fr via /api/me. Backend 460, frontend 455/70, build 621.30kB.`
- Frontend Conventions: UI text goes through `'key' | t` (templates) or `translate('key')` (TS); never hardcode English. Adding a language = `public/i18n/xx.json` + `SUPPORTED_LOCALES` + backend allow-list in `UpdateProfileRequest`; the completeness spec enforces keys and placeholders. Plurals use `key_one/key_other`.
- Data Model: `users.locale` (V30) varchar(8) default 'en'.
- API: `/me` carries `locale`.

## Flake fix (`profile-language.spec.ts`)
- Red: reproduced in full runs (about half of them): `expected [] to deeply equal ['en','hi','es','fr']`. Debug output showed
  the component's `locales` property was `undefined` while `SUPPORTED_LOCALES.length` was 4, i.e. the class field was
  initialised before the imported constant was available (module evaluation order differs between full and isolated runs).
  Not shared signal/localStorage state and not an unflushed request.
- Fix: `ProfileComponent.locales` is now a getter (read lazily, like the existing `currencies`), so evaluation order cannot matter.
- Green: spec looped 20 times alone, 20/20 pass; 8 consecutive full runs, 455/455 each time.
