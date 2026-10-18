import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SessionService } from '../core/auth/session.service';
import { PaymentService } from './payment.service';

describe('PaymentService', () => {
  let service: PaymentService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PaymentService);
    httpTesting = TestBed.inject(HttpTestingController);
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

  it('starts a new operation when any submitted intent field changes', () => {
    const requests: [number, string, string, string][] = [
      [800, 'g1', 'u1', 'u2'],
      [900, 'g1', 'u1', 'u2'],
      [900, 'g2', 'u1', 'u2'],
      [900, 'g2', 'u3', 'u2'],
      [900, 'g2', 'u3', 'u4'],
    ];
    const ids = requests.map((args) => {
      const req = send(...args);
      const id = req.request.body.operationId;
      expect(id).toEqual(expect.any(String));
      req.error(new ProgressEvent('error'));
      return id;
    });
    expect(new Set(ids).size).toBe(requests.length);
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
