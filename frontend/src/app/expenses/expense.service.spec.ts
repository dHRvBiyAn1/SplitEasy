import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ExpenseService, centsToDisplay, dollarsToCents, percentToBasisPoints } from './expense.service';

describe('ExpenseService', () => {
  let service: ExpenseService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ExpenseService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('lists expenses for a group', () => {
    service.listExpenses('g1').subscribe();
    const req = httpTesting.expectOne('/api/groups/g1/expenses');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('creates an expense with cents payload', () => {
    service
      .createExpense('g1', {
        description: 'Dinner',
        amountCents: 1000,
        paidByUserId: 'u1',
        participantUserIds: ['u1', 'u2'],
        splitType: 'EQUAL',
        splits: null,
      })
      .subscribe();
    const req = httpTesting.expectOne('/api/groups/g1/expenses');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.amountCents).toBe(1000);
    expect(req.request.body.participantUserIds).toEqual(['u1', 'u2']);
    req.flush({});
  });

  it('gets a single expense', () => {
    service.getExpense('g1', 'e1').subscribe();
    const req = httpTesting.expectOne('/api/groups/g1/expenses/e1');
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('updates an expense via PUT', () => {
    service
      .updateExpense('g1', 'e1', {
        description: 'Dinner',
        amountCents: 2000,
        paidByUserId: 'u1',
        participantUserIds: ['u1', 'u2'],
        splitType: 'EQUAL',
        splits: null,
      })
      .subscribe();
    const req = httpTesting.expectOne('/api/groups/g1/expenses/e1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.amountCents).toBe(2000);
    req.flush({});
  });

  it('deletes an expense via DELETE', () => {
    service.deleteExpense('g1', 'e1').subscribe();
    const req = httpTesting.expectOne('/api/groups/g1/expenses/e1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});

describe('money helpers', () => {
  it('dollarsToCents rounds to integer cents without float drift', () => {
    expect(dollarsToCents(10)).toBe(1000);
    expect(dollarsToCents(10.01)).toBe(1001);
    // 19.99 * 100 is 1998.9999... in binary float; must round to 1999.
    expect(dollarsToCents(19.99)).toBe(1999);
    expect(dollarsToCents(0.1 + 0.2)).toBe(30);
  });

  it('centsToDisplay formats cents as a 2dp string', () => {
    expect(centsToDisplay(1000)).toBe('10.00');
    expect(centsToDisplay(1001)).toBe('10.01');
    expect(centsToDisplay(5)).toBe('0.05');
    expect(centsToDisplay(334)).toBe('3.34');
  });

  it('percentToBasisPoints converts percent to integer basis points', () => {
    expect(percentToBasisPoints(50)).toBe(5000);
    expect(percentToBasisPoints(33.33)).toBe(3333);
    expect(percentToBasisPoints(33.34)).toBe(3334);
    // 3333 + 3333 + 3334 must land exactly on 10000
    expect(percentToBasisPoints(33.33) * 2 + percentToBasisPoints(33.34)).toBe(10000);
  });
});
