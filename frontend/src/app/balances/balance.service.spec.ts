import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { BalanceService, describeBalance } from './balance.service';

describe('BalanceService', () => {
  let service: BalanceService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(BalanceService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('fetches balances for a group', () => {
    service.getBalances('g1').subscribe();
    const req = httpTesting.expectOne('/api/groups/g1/balances');
    expect(req.request.method).toBe('GET');
    req.flush({ groupId: 'g1', balances: [] });
  });
});

describe('describeBalance', () => {
  it('phrases a positive balance as "is owed"', () => {
    expect(describeBalance(1250)).toBe('is owed $12.50');
  });

  it('phrases a negative balance as "owes"', () => {
    expect(describeBalance(-800)).toBe('owes $8.00');
  });

  it('phrases a zero balance as settled', () => {
    expect(describeBalance(0)).toBe('is settled up');
  });

  it('pads single-digit cents', () => {
    expect(describeBalance(305)).toBe('is owed $3.05');
    expect(describeBalance(-5)).toBe('owes $0.05');
  });
});
