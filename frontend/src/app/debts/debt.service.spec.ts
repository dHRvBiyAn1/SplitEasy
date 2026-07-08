import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DebtService } from './debt.service';

describe('DebtService', () => {
  let service: DebtService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(DebtService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('GETs the debt-simplification endpoint for a group', () => {
    service.getSimplifiedDebts('g1').subscribe();
    const req = httpTesting.expectOne('/api/groups/g1/debt-simplification');
    expect(req.request.method).toBe('GET');
    req.flush({ groupId: 'g1', transactions: [] });
  });
});
