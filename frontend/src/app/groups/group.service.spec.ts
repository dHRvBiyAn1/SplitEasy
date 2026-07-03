import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { GroupService } from './group.service';

describe('GroupService', () => {
  let service: GroupService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(GroupService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('listMyGroups GETs /api/groups', () => {
    service.listMyGroups().subscribe();
    const req = httpTesting.expectOne('/api/groups');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('createGroup POSTs the name', () => {
    service.createGroup('Trip to Goa').subscribe();
    const req = httpTesting.expectOne('/api/groups');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ name: 'Trip to Goa' });
    req.flush({});
  });

  it('addMember POSTs the email to the group members endpoint', () => {
    service.addMember('g1', 'friend@example.com').subscribe();
    const req = httpTesting.expectOne('/api/groups/g1/members');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'friend@example.com' });
    req.flush({});
  });

  it('getGroup GETs a single group', () => {
    service.getGroup('g1').subscribe();
    const req = httpTesting.expectOne('/api/groups/g1');
    expect(req.request.method).toBe('GET');
    req.flush({});
  });
});
