import { Component, computed, input } from '@angular/core';

import { Currency, CURRENCY_EXPONENT, minorToDisplay } from './currency';

/** Format integer units exactly, including zero/three-decimal currencies and large totals. */
export function formatMoney(minor: number, net = false, currency: Currency = 'USD'): string {
  if (!Number.isSafeInteger(minor)) return 'Unavailable';
  const sign = minor < 0 ? '−' : net && minor > 0 ? '+' : '';
  const [whole, fraction] = minorToDisplay(Math.abs(minor), currency).split('.');
  const exponent = CURRENCY_EXPONENT[currency];
  const text = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency,
    currencyDisplay: currency === 'USD' ? 'symbol' : 'code',
    minimumFractionDigits: exponent,
    maximumFractionDigits: exponent,
  })
    .formatToParts(0)
    .map((part) =>
      part.type === 'integer'
        ? Number(whole).toLocaleString('en-US')
        : part.type === 'fraction'
          ? fraction
          : part.value,
    )
    .join('');
  return sign + text;
}
@Component({
  selector: 'app-money',
  template: '{{ text() }}',
  host: {
    class: 'dc-amount',
    '[class.dc-amount--owed]': 'net() && minor()>0',
    '[class.dc-amount--owes]': 'net() && minor()<0',
    '[class.dc-amount--settled]': 'net() && minor()===0',
    '[style.font-size]': "long()?'var(--money-long-size,1rem)':null",
    '[class.money--long]': 'long()',
    '[attr.tabindex]': 'long()?0:null',
    '[attr.aria-label]': 'long()?text():null',
    '[attr.title]': 'text()',
  },
  styles:
    ':host{display:inline-block;max-width:100%;vertical-align:bottom;white-space:nowrap;font-variant-numeric:tabular-nums}:host(.money--long){overflow-x:auto;scrollbar-width:thin}',
})
export class MoneyComponent {
  readonly minor = input.required<number>();
  readonly net = input(false);
  readonly currency = input<Currency>('USD');
  readonly text = computed(() => formatMoney(this.minor(), this.net(), this.currency()));
  readonly long = computed(() => this.text().length > 13);
}
