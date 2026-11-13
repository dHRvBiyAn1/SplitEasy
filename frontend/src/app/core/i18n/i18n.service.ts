import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { SessionService } from '../auth/session.service';
import en from '../../../../public/i18n/en.json';

/** The single list to extend: add a code here, ship public/i18n/<code>.json and allow it in the backend. */
export const SUPPORTED_LOCALES = ['en', 'hi', 'es', 'fr'] as const;
export type Locale = (typeof SUPPORTED_LOCALES)[number];
export type Catalogue = Record<string, string>;
export type Params = Record<string, string | number | null | undefined>;

export const isLocale = (value: unknown): value is Locale =>
  SUPPORTED_LOCALES.includes(value as Locale);

/** First supported browser language, else English. */
export function browserLocale(): Locale {
  for (const tag of globalThis.navigator?.languages ?? []) {
    const base = tag.toLowerCase().split('-')[0];
    if (isLocale(base)) return base;
  }
  return 'en';
}

/** Read by formatting helpers so any computed/template that formats re-renders on a switch. */
export const activeLocale = signal<Locale>(browserLocale());

/** BCP 47 tag for Intl; Hindi gets Indian digit grouping. */
export const intlLocale = (locale: Locale = activeLocale()): string =>
  locale === 'en' ? 'en-US' : locale === 'hi' ? 'hi-IN' : locale;

@Injectable({ providedIn: 'root' })
export class I18nService {
  private readonly http = inject(HttpClient);
  readonly locale = activeLocale.asReadonly();
  private readonly catalogues = signal<Partial<Record<Locale, Catalogue>>>({ en });

  constructor() {
    inject(SessionService).changes.subscribe(() => this.setLocale(browserLocale()));
    this.setLocale(activeLocale());
  }

  /** Instant switch: the signal flips first, the catalogue arrives lazily and English covers the gap. */
  setLocale(locale: string): void {
    if (!isLocale(locale)) return;
    activeLocale.set(locale);
    document.documentElement.lang = locale;
    if (!this.catalogues()[locale]) {
      this.http.get<Catalogue>(`/i18n/${locale}.json`).subscribe({
        next: (cat) => this.extend(locale, cat),
        error: () => undefined, // English fallback stays in place
      });
    }
  }

  extend(locale: Locale, entries: Catalogue): void {
    this.catalogues.update((all) => ({ ...all, [locale]: { ...all[locale], ...entries } }));
  }

  private lookup(locale: Locale, key: string, count: unknown): string | undefined {
    const cat = this.catalogues()[locale];
    if (!cat) return undefined;
    if (typeof count === 'number') {
      const category = new Intl.PluralRules(intlLocale(locale)).select(count);
      return cat[`${key}_${category}`] ?? cat[`${key}_other`] ?? cat[key];
    }
    return cat[key];
  }

  /** Missing keys fall back to English, never to the raw key (unless English lacks it too). */
  t(key: string, params?: Params): string {
    const count = params?.['count'];
    const raw = this.lookup(this.locale(), key, count) ?? this.lookup('en', key, count);
    return (raw ?? key).replace(/\{(\w+)\}/g, (_, name) => String(params?.[name] ?? ''));
  }
}
