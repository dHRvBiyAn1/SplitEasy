import { describe, expect, it, beforeEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { RecurringComponent } from './recurring.component';
import { RecurringSchedule } from './recurring.models';
import { AuthService } from '../core/auth/auth.service';
import { storeTestSession } from '../core/auth/auth-test-fixture';

function schedule(overrides: Partial<RecurringSchedule> = {}): RecurringSchedule {
  return {
    id: 's1',
    groupId: 'group-1',
    createdBy: 'alice',
    template: {
      description: 'Rent',
      amountMinor: 120000,
      currency: 'USD',
      paidByUserId: 'alice',
      participantUserIds: ['alice', 'bob'],
    },
    frequency: 'MONTHLY',
    anchorDate: '2026-03-01',
    timeZone: 'UTC',
    nextDate: '2026-04-01',
    version: 2,
    status: 'ACTIVE',
    reviewReason: null,
    createdAt: '2026-03-01T00:00:00Z',
    cancelledAt: null,
    ...overrides,
  };
}

describe('RecurringComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ id: 'group-1' })) },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    storeTestSession({ id: 'alice', email: 'alice@example.com', displayName: 'Alice' });
    vi.spyOn(TestBed.inject(AuthService), 'isAuthenticated').mockReturnValue(true);
  });

  async function render(list: RecurringSchedule[]) {
    const fixture = TestBed.createComponent(RecurringComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/group-1/recurring').flush(list);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  const button = (el: HTMLElement, label: string) =>
    Array.from(el.querySelectorAll('button')).find((b) =>
      b.textContent?.includes(label),
    ) as HTMLButtonElement;

  it('lists schedules and shows the reason a halted one needs review', async () => {
    const fixture = await render([
      schedule(),
      schedule({ id: 's2', status: 'NEEDS_REVIEW', reviewReason: 'Bob is not an active member' }),
    ]);
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('Rent');
    expect(el.textContent).toContain('USD 1200.00');
    expect(el.textContent).toContain('Every month');
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Bob is not an active member');
  });

  it('edits future occurrences at the version it loaded', async () => {
    const fixture = await render([schedule()]);
    const el: HTMLElement = fixture.nativeElement;
    button(el, 'Edit future').click();
    fixture.detectChanges();
    const amount = el.querySelector('input[name=amount]') as HTMLInputElement;
    amount.value = '1300';
    amount.dispatchEvent(new Event('input'));
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne('/api/groups/group-1/recurring/s1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.expectedVersion).toBe(2);
    expect(req.request.body.template.amountMinor).toBe(130000);
    req.flush(
      schedule({
        version: 3,
        template: { ...schedule().template, amountMinor: 130000 } as RecurringSchedule['template'],
      }),
    );
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.textContent).toContain('USD 1300.00');
  });

  it('cancels a schedule and then offers no further actions', async () => {
    const fixture = await render([schedule()]);
    const el: HTMLElement = fixture.nativeElement;
    button(el, 'Cancel').click();
    const req = http.expectOne('/api/groups/group-1/recurring/s1/cancel');
    expect(req.request.method).toBe('POST');
    req.flush(schedule({ status: 'CANCELLED', cancelledAt: '2026-03-02T00:00:00Z' }));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.textContent).toContain('Cancelled');
    expect(button(el, 'Edit future')).toBeUndefined();
  });
});
