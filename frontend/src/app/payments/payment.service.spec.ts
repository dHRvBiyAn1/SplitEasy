import { storeTestSession } from '../core/auth/auth-test-fixture';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SessionService } from '../core/auth/session.service';
import { PaymentService } from './payment.service';

describe('PaymentService', () => {
  let service: PaymentService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PaymentService);
    httpTesting = TestBed.inject(HttpTestingController);
    storeTestSession({ id: 'u1', email: 'owner@example.test', displayName: 'Owner' });
  });

  afterEach(() => httpTesting.verify());

  function send(amountCents = 800, group = 'g1', payerUserId = 'u1', payeeUserId = 'u2') {
    service
      .recordPayment(group, { payerUserId, payeeUserId, amountCents })
      .subscribe({ error: () => {} });
    return httpTesting.expectOne(`/api/groups/${group}/payments`);
  }

  it('reuses an uncertain operation across errors and resubscriptions; success starts a fresh one', () => {
    const pending = service.recordPayment('g1', {
      payerUserId: 'u1',
      payeeUserId: 'u2',
      amountCents: 800,
    });
    pending.subscribe({ error: () => {} });
    const first = httpTesting.expectOne('/api/groups/g1/payments');
    const body = first.request.body;
    expect(body.operationId).toEqual(expect.any(String));
    first.error(new ProgressEvent('error'));
    pending.subscribe({ error: () => {} });
    const second = httpTesting.expectOne('/api/groups/g1/payments');
    expect(second.request.body).toEqual(body);
    second.flush('unavailable', { status: 503, statusText: 'Unavailable' });
    const retry = send();
    expect(retry.request.body).toEqual(body);
    retry.flush({});
    const repeat = send();
    expect(repeat.request.body.operationId).not.toBe(body.operationId);
    repeat.flush({});
  });

  it('blocks every changed uncertain intent field and preserves the original retry identity', () => {
    const original = send();
    const originalBody = original.request.body;
    original.error(new ProgressEvent('error'));
    const changes: [number, string, string, string][] = [
      [900, 'g1', 'u1', 'u2'],
      [800, 'g2', 'u1', 'u2'],
      [800, 'g1', 'u3', 'u2'],
      [800, 'g1', 'u1', 'u4'],
    ];
    for (const [amountCents, group, payerUserId, payeeUserId] of changes) {
      let error: Error | undefined;
      service.recordPayment(group, { payerUserId, payeeUserId, amountCents }).subscribe({
        error: (failure) => {
          error = failure;
        },
      });
      expect(error?.message).toContain('may already have been saved');
      httpTesting.expectNone((request) => request.method === 'POST');
    }
    const retry = send();
    expect(retry.request.body).toEqual(originalBody);
    retry.flush({});
  });

  it('clears pending operation on session reset and cancels old requests', () => {
    const old = send();
    const oldId = old.request.body.operationId;
    expect(oldId).toEqual(expect.any(String));
    TestBed.inject(SessionService).reset();
    expect(old.cancelled).toBe(true);
    const current = send();
    expect(current.request.body.operationId).not.toBe(oldId);
    current.flush({});
  });

  it('does not send an old observable under a new account', () => {
    const old = service.recordPayment('g1', {
      payerUserId: 'u1',
      payeeUserId: 'u2',
      amountCents: 800,
    });
    TestBed.inject(SessionService).reset();
    old.subscribe();
    httpTesting.expectNone('/api/groups/g1/payments');
  });

  it('lists payments for a group', () => {
    service.listPayments('g1').subscribe();
    const req = httpTesting.expectOne('/api/groups/g1/payments');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('records a payment with payer/payee/amount', () => {
    service
      .recordPayment('g1', { payerUserId: 'u1', payeeUserId: 'u2', amountCents: 800 })
      .subscribe();
    const req = httpTesting.expectOne('/api/groups/g1/payments');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      payerUserId: 'u1',
      payeeUserId: 'u2',
      amountCents: 800,
      operationId: expect.any(String),
    });
    expect(req.request.body.operationId).toMatch(/^[0-9a-f-]{36}$/);
    req.flush({});
  });
});
