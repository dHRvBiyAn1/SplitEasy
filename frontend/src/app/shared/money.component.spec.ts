import { TestBed } from '@angular/core/testing';
import { MoneyComponent, formatMoney } from './money.component';
describe('integer money presentation', () => {
  it('keeps cents, grouping and signs exact', () => {
    expect(formatMoney(123456789)).toBe('$1,234,567.89');
    expect(formatMoney(-1, true)).toBe('−$0.01');
    expect(formatMoney(0, true)).toBe('$0.00');
    expect(formatMoney(999999999999)).toBe('$9,999,999,999.99');
  });
  it('colors net balances while keeping plain totals neutral', () => {
    const f = TestBed.createComponent(MoneyComponent);
    f.componentRef.setInput('cents', 100);
    f.detectChanges();
    expect(f.nativeElement.classList.contains('dc-amount--owed')).toBe(false);
    f.componentRef.setInput('net', true);
    f.detectChanges();
    expect(f.nativeElement.classList.contains('dc-amount--owed')).toBe(true);
    f.componentRef.setInput('cents', -1);
    f.detectChanges();
    expect(f.nativeElement.classList.contains('dc-amount--owes')).toBe(true);
  });
});
