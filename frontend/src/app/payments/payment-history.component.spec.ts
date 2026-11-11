import { describe, expect, it, beforeEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { PaymentHistoryComponent } from './payment-history.component';
import { DeletedPayment, LedgerRevision, PaymentResponse } from './payment.models';
import { AuthService } from '../core/auth/auth.service';
import { storeTestSession } from '../core/auth/auth-test-fixture';

function participant(id: string, name: string) {
  return {
    id,
    email: `${name.toLowerCase()}@example.com`,
    displayName: name,
    canonicalParticipantId: id,
    accountId: id,
  };
}

function payment(overrides: Partial<PaymentResponse> = {}): PaymentResponse {
  return {
    id: 'pay-1',
    payer: participant('bob', 'Bob'),
    payee: participant('alice', 'Alice'),
    amountMinor: 2500,
    currency: 'USD',
    createdAt: '2026-03-04T10:00:00Z',
    paidOn: '2026-03-04',
    method: 'BANK_TRANSFER',
    note: 'Split the deposit back',
    recordedBy: { id: 'bob', email: 'bob@example.com', displayName: 'Bob' },
    version: 0,
    deletedAt: null,
    ...overrides,
  };
}

describe('PaymentHistoryComponent', () => {
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

  function render() {
    const fixture = TestBed.createComponent(PaymentHistoryComponent);
    fixture.detectChanges();
    return fixture;
  }

  function settle(payments: PaymentResponse[], deleted: DeletedPayment[] = []) {
    http.expectOne('/api/groups/group-1/payments').flush(payments);
    http.expectOne('/api/groups/group-1/payments/deleted').flush(deleted);
  }

  it('shows each settlement with its financial date, method, note and recorder', () => {
    const fixture = render();
    settle([payment()]);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Bob');
    expect(text).toContain('Alice');
    expect(text).toContain('$25.00');
    expect(text).toContain('Bank transfer');
    expect(text).toContain('Split the deposit back');
    expect(text).toContain('Recorded by Bob');
  });

  it('sends a correction at the version it saw and reloads the list', () => {
    const fixture = render();
    settle([payment()]);
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    element.querySelector<HTMLButtonElement>('[data-test="correct-pay-1"]')!.click();
    fixture.detectChanges();
    const amount = element.querySelector<HTMLInputElement>('[data-test="correct-amount"]')!;
    amount.value = '30.00';
    amount.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    element
      .querySelector<HTMLFormElement>('[data-test="correct-form"]')!
      .dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    const request = http.expectOne('/api/groups/group-1/payments/pay-1');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toMatchObject({ amountMinor: 3000, expectedVersion: 0 });
    request.flush(payment({ amountMinor: 3000, version: 1 }));
    fixture.detectChanges();
    settle([payment({ amountMinor: 3000, version: 1 })]);
  });

  it('keeps a stale correction open with an actionable conflict message', () => {
    const fixture = render();
    settle([payment()]);
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    element.querySelector<HTMLButtonElement>('[data-test="correct-pay-1"]')!.click();
    fixture.detectChanges();
    element
      .querySelector<HTMLFormElement>('[data-test="correct-form"]')!
      .dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    http
      .expectOne('/api/groups/group-1/payments/pay-1')
      .flush({ message: 'changed' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(element.querySelector('[data-test="correct-form"]')).not.toBeNull();
    expect((element.textContent ?? '').toLowerCase()).toContain('reload');
  });

  it('undoes a settlement and restores it from the undone section', () => {
    const fixture = render();
    settle([payment()]);
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    element.querySelector<HTMLButtonElement>('[data-test="undo-pay-1"]')!.click();
    fixture.detectChanges();
    element.querySelector<HTMLButtonElement>('[data-test="confirm-undo"]')!.click();
    fixture.detectChanges();
    const removal = http.expectOne('/api/groups/group-1/payments/pay-1?expectedVersion=0');
    expect(removal.request.method).toBe('DELETE');
    removal.flush(null);
    fixture.detectChanges();
    settle(
      [],
      [
        {
          payment: payment({ version: 1, deletedAt: '2026-03-05T09:00:00Z' }),
          deletedAt: '2026-03-05T09:00:00Z',
        },
      ],
    );
    fixture.detectChanges();

    expect(element.textContent).toContain('Undone settlements');
    element.querySelector<HTMLButtonElement>('[data-test="restore-pay-1"]')!.click();
    fixture.detectChanges();
    const restore = http.expectOne('/api/groups/group-1/payments/pay-1/restore');
    expect(restore.request.body).toEqual({ expectedVersion: 1 });
    restore.flush(payment({ version: 2 }));
    fixture.detectChanges();
    settle([payment({ version: 2 })]);
  });

  it('shows one settlement’s attributable revisions on request', () => {
    const fixture = render();
    settle([payment({ version: 1 })]);
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    element.querySelector<HTMLButtonElement>('[data-test="revisions-pay-1"]')!.click();
    fixture.detectChanges();
    const revisions: LedgerRevision<PaymentResponse>[] = [
      {
        action: 'CREATED',
        actor: { id: 'bob', email: 'bob@example.com', displayName: 'Bob' },
        recordedAt: '2026-03-04T10:00:00Z',
        version: 0,
        operationId: null,
        before: null,
        after: payment(),
      },
      {
        action: 'CORRECTED',
        actor: { id: 'alice', email: 'alice@example.com', displayName: 'Alice' },
        recordedAt: '2026-03-05T10:00:00Z',
        version: 1,
        operationId: null,
        before: payment(),
        after: payment({ amountMinor: 3000, version: 1 }),
      },
    ];
    http.expectOne('/api/groups/group-1/payments/pay-1/history').flush(revisions);
    fixture.detectChanges();

    const text = element.textContent ?? '';
    expect(text).toContain('Corrected');
    expect(text).toContain('Alice');
  });

  it('says plainly that recording a settlement moves no money', () => {
    const fixture = render();
    settle([]);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent?.toLowerCase()).toContain(
      'does not move any money',
    );
  });
});
