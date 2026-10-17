import { Component, computed, input } from '@angular/core';

/** Display-only formatting from integer cents; never used to construct a form payload. */
export function formatMoney(cents: number, net = false): string {
  const abs = Math.abs(cents);
  const sign = cents < 0 ? '−' : net && cents > 0 ? '+' : '';
  return `${sign}$${Math.floor(abs / 100).toLocaleString('en-US')}.${String(abs % 100).padStart(2, '0')}`;
}
@Component({
  selector: 'app-money',
  template: '{{ text() }}',
  host: {
    class: 'dc-amount',
    '[class.dc-amount--owed]': 'net() && cents()>0',
    '[class.dc-amount--owes]': 'net() && cents()<0',
    '[class.dc-amount--settled]': 'net() && cents()===0',
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
  readonly cents = input.required<number>();
  readonly net = input(false);
  readonly text = computed(() => formatMoney(this.cents(), this.net()));
  readonly long = computed(() => this.text().length > 13);
}
