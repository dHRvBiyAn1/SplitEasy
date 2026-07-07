import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
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
    expect(req.request.body).toEqual({ payerUserId: 'u1', payeeUserId: 'u2', amountCents: 800 });
    req.flush({});
  });
});
