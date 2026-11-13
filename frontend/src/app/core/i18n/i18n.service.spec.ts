import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { I18nService, SUPPORTED_LOCALES, activeLocale } from './i18n.service';
import { TranslatePipe } from './translate.pipe';
import { SessionService } from '../auth/session.service';
import { MoneyComponent, formatMoney } from '../../shared/money.component';
import en from '../../../../public/i18n/en.json';
import hi from '../../../../public/i18n/hi.json';
import es from '../../../../public/i18n/es.json';
import fr from '../../../../public/i18n/fr.json';

const catalogues: Record<string, Record<string, string>> = { hi, es, fr };
const placeholders = (text: string) => [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();

describe('catalogue completeness', () => {
  it('lists a catalogue for every supported locale', () => {
    expect(Object.keys(catalogues).sort()).toEqual(
      SUPPORTED_LOCALES.filter((l) => l !== 'en').sort(),
    );
  });

  for (const locale of Object.keys(catalogues)) {
    it(`${locale} has exactly the English keys and placeholders`, () => {
      const source = en as Record<string, string>;
      const target = catalogues[locale];
      const base = (k: string) => k.replace(/_(zero|one|two|few|many|other)$/, '');
      const sourceBases = new Set(Object.keys(source).map(base));
      // Plural categories differ per language; every other key must match exactly.
      const plain = (o: Record<string, string>) =>
        Object.keys(o)
          .filter((k) => !/_(zero|one|two|few|many|other)$/.test(k))
          .sort();
      expect(plain(target)).toEqual(plain(source));
      expect(Object.keys(target).filter((k) => !sourceBases.has(base(k)))).toEqual([]);
      for (const key of Object.keys(source).filter((k) => /_one$|_other$/.test(k))) {
        expect(target[key.replace(/_one$/, '_other')]).toBeDefined();
      }
      const bad = Object.keys(target).filter((k) => {
        const ref = source[k] ?? source[`${base(k)}_other`];
        return placeholders(target[k]).join() !== placeholders(ref).join();
      });
      expect(bad).toEqual([]);
    });
  }
});

@Component({
  imports: [TranslatePipe, MoneyComponent],
  template: `<p id="a">{{ 'spec.hello' | t: { name: 'Ann' } }}</p>
    <p id="b">{{ 'spec.only_en' | t }}</p>
    <p id="c">{{ 'spec.count' | t: { count: n } }}</p>
    <app-money [minor]="12345678" currency="INR" />`,
})
class Host {
  n = 1;
}

describe('I18nService', () => {
  let http: HttpTestingController;
  let i18n: I18nService;
  const patch = (locale: 'hi' | 'es' | 'fr', body: Record<string, string>) => {
    i18n.setLocale(locale);
    http.expectOne(`/i18n/${locale}.json`).flush(body);
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    i18n = TestBed.inject(I18nService);
    i18n.setLocale('en');
    i18n.extend('en', {
      'spec.hello': 'Hello {name}',
      'spec.only_en': 'English only',
      'spec.count_one': '{count} item',
      'spec.count_other': '{count} items',
    });
  });
  afterEach(() => activeLocale.set('en'));

  it('translates with interpolation and plurals and falls back to English per key', () => {
    patch('es', {
      'spec.hello': 'Hola {name}',
      'spec.count_one': '{count} elemento',
      'spec.count_other': '{count} elementos',
    });
    const f = TestBed.createComponent(Host);
    f.detectChanges();
    const text = (id: string) => f.nativeElement.querySelector(id).textContent;
    expect(text('#a')).toBe('Hola Ann');
    expect(text('#b')).toBe('English only');
    expect(text('#c')).toBe('1 elemento');
    f.componentInstance.n = 3;
    f.detectChanges();
    expect(text('#c')).toBe('3 elementos');
    expect(document.documentElement.lang).toBe('es');
  });

  it('switches instantly when the locale changes and never shows a raw key', () => {
    const f = TestBed.createComponent(Host);
    f.detectChanges();
    expect(f.nativeElement.querySelector('#a').textContent).toBe('Hello Ann');
    patch('fr', { 'spec.hello': 'Bonjour {name}' });
    f.detectChanges();
    expect(f.nativeElement.querySelector('#a').textContent).toBe('Bonjour Ann');
    expect(i18n.t('no.such.key')).toBe('no.such.key');
  });

  it('formats money with Indian grouping in Hindi without changing the amount', () => {
    expect(formatMoney(12345678, false, 'INR')).toContain('123,456.78');
    patch('hi', {});
    expect(formatMoney(12345678, false, 'INR')).toContain('1,23,456.78');
    expect(formatMoney(12345678, false, 'USD')).toBe('$1,23,456.78');
    patch('fr', {});
    expect(formatMoney(12345678, false, 'USD')).toMatch(/^123\s456,78/);
  });

  it('renders app-money with Indian grouping in Hindi and keeps the digits in English', () => {
    const f = TestBed.createComponent(Host);
    f.detectChanges();
    const money = () => f.nativeElement.querySelector('app-money').textContent.trim();
    expect(money()).toMatch(/^INR\s123,456\.78$/);
    patch('hi', {});
    f.detectChanges();
    expect(money()).toMatch(/^INR\s1,23,456\.78$/);
  });

  it('returns to the browser default when the session resets', () => {
    patch('hi', {});
    TestBed.inject(SessionService).reset();
    expect(i18n.locale()).toBe('en');
  });

  it('ignores unsupported locales and keeps English when a catalogue fails to load', () => {
    i18n.setLocale('de');
    expect(i18n.locale()).toBe('en');
    i18n.setLocale('fr');
    http.expectOne('/i18n/fr.json').error(new ProgressEvent('error'));
    expect(i18n.t('spec.hello', { name: 'Ann' })).toBe('Hello Ann');
  });
});
