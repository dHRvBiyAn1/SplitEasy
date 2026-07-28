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

  it('fans out over several groups and emits their results together', () => {
    let emitted: unknown[] | undefined;
    service.getSimplifiedDebtsForGroups(['g1', 'g2']).subscribe((res) => (emitted = res));

    httpTesting.expectOne('/api/groups/g1/debt-simplification').flush({ groupId: 'g1', transactions: [] });
    expect(emitted).toBeUndefined(); // waits for every group before emitting
    httpTesting.expectOne('/api/groups/g2/debt-simplification').flush({ groupId: 'g2', transactions: [] });

    expect(emitted).toHaveLength(2);
  });

  it('emits an empty list without calling the API when there are no groups', () => {
    let emitted: unknown[] | undefined;
    service.getSimplifiedDebtsForGroups([]).subscribe((res) => (emitted = res));

    expect(emitted).toEqual([]); // forkJoin([]) would never emit
  });
});
