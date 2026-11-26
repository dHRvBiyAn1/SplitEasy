import { describe, expect, it, beforeEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AnalyticsComponent } from './analytics.component';
import { AnalyticsRow } from './analytics.models';
import { AuthService } from '../core/auth/auth.service';
import { storeTestSession } from '../core/auth/auth-test-fixture';

const row = (over: Partial<AnalyticsRow>): AnalyticsRow => ({
  currency: 'USD',
  key: 'FOOD_DRINK',
  label: 'FOOD_DRINK',
  groupTotalMinor: 8100,
  myShareMinor: 2700,
  myPaidMinor: 8100,
  ...over,
});

describe('AnalyticsComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { queryParamMap: of(convertToParamMap({ groupId: 'g1' })) },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    storeTestSession({ id: 'alice', email: 'alice@example.com', displayName: 'Alice' });
    vi.spyOn(TestBed.inject(AuthService), 'isAuthenticated').mockReturnValue(true);
  });

  function render() {
    const fixture = TestBed.createComponent(AnalyticsComponent);
    fixture.detectChanges();
    return fixture;
  }
  const pending = (query: string) =>
    http.expectOne((r) => r.urlWithParams === `/api/analytics?${query}`);

  it('shows one section per currency with CSS bars and a table carrying the same numbers', async () => {
    const fixture = render();
    pending('groupBy=category&groupId=g1').flush({
      rows: [
        row({}),
        row({ key: 'TRANSPORT', label: 'TRANSPORT', groupTotalMinor: 2700 }),
        row({ currency: 'KWD', key: 'FOOD_DRINK', groupTotalMinor: 1000 }),
      ],
    });
    await fixture.whenStable();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;

    expect(el.querySelectorAll('section.currency')).toHaveLength(2);
    expect(el.querySelectorAll('section.currency')[0].querySelectorAll('tbody tr')).toHaveLength(2);
    const bars = el
      .querySelectorAll<HTMLElement>('section.currency')[0]
      .querySelectorAll<HTMLElement>('.bar__fill');
    expect(bars[0].style.width).toBe('100%');
    expect(bars[1].style.width).toBe('33.3333%');
    expect(bars[0].closest('[aria-hidden="true"]')).not.toBeNull();
    expect(el.querySelector('tbody tr')!.textContent).toContain('$81.00');
    expect(el.textContent).toContain('KWD');
  });

  it('keeps negative-after-refund values readable and an empty period explained', async () => {
    const fixture = render();
    pending('groupBy=category&groupId=g1').flush({
      rows: [row({ groupTotalMinor: -500, myShareMinor: -250, myPaidMinor: 0 })],
    });
    await fixture.whenStable();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('tbody tr')!.textContent).toContain('−$5.00');
    expect(el.querySelector<HTMLElement>('.bar__fill')!.classList).toContain('bar__fill--negative');

    (el.querySelector('input[name=from]') as HTMLInputElement).value = '2026-05-01';
    el.querySelector('input[name=from]')!.dispatchEvent(new Event('input'));
    el.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    pending('groupBy=category&groupId=g1&from=2026-05-01').flush({ rows: [] });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.textContent).toContain('No spending in this period');
  });

  it('offers a retry when the read fails', async () => {
    const fixture = render();
    pending('groupBy=category&groupId=g1').flush('x', { status: 500, statusText: 'err' });
    await fixture.whenStable();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Could not load analytics');
    (el.querySelector('.retry button') as HTMLButtonElement).click();
    pending('groupBy=category&groupId=g1').flush({ rows: [] });
  });
});
