import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DashboardService } from './dashboard.service';
import { SessionService } from '../core/auth/session.service';

const empty = {
  totalNetCents: 0,
  owedCents: 0,
  owedPeopleCount: 0,
  oweCents: 0,
  owePeopleCount: 0,
  groupCount: 0,
  groups: [],
  people: [],
  settlements: [],
  activity: [],
};
describe('overview refresh presentation', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('keeps trusted data visible when a refresh fails', () => {
    const dashboard = TestBed.inject(DashboardService),
      http = TestBed.inject(HttpTestingController);
    dashboard.ensureLoaded();
    http.expectOne('/api/dashboard').flush(empty);
    dashboard.refresh();
    http.expectOne('/api/dashboard').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(dashboard.data()).toEqual(empty);
    expect(dashboard.loading()).toBe(false);
    expect(dashboard.error()).toBe(true);
  });
  it('stops after consecutive initial failures until an explicit retry succeeds', () => {
    const dashboard = TestBed.inject(DashboardService),
      http = TestBed.inject(HttpTestingController);

    dashboard.ensureLoaded();
    http.expectOne('/api/dashboard').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(dashboard.data()).toBeNull();
    expect(dashboard.loading()).toBe(false);
    expect(dashboard.error()).toBe(true);

    dashboard.ensureLoaded();
    dashboard.ensureLoaded();
    http.expectNone('/api/dashboard');

    dashboard.refresh();
    expect(dashboard.loading()).toBe(true);
    expect(dashboard.error()).toBe(false);
    http.expectOne('/api/dashboard').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(dashboard.loading()).toBe(false);
    expect(dashboard.error()).toBe(true);
    expect(dashboard.data()).toBeNull();

    dashboard.ensureLoaded();
    http.expectNone('/api/dashboard');

    dashboard.refresh();
    http.expectOne('/api/dashboard').flush(empty);
    expect(dashboard.data()).toEqual(empty);
    expect(dashboard.loading()).toBe(false);
    expect(dashboard.error()).toBe(false);
  });
  it('never restores the previous account cache from a late failed refresh', () => {
    const dashboard = TestBed.inject(DashboardService),
      http = TestBed.inject(HttpTestingController);
    dashboard.ensureLoaded();
    http.expectOne('/api/dashboard').flush(empty);
    dashboard.refresh();
    const pending = http.expectOne('/api/dashboard');
    TestBed.inject(SessionService).reset();
    pending.flush({}, { status: 503, statusText: 'Unavailable' });
    expect(dashboard.data()).toBeNull();
    expect(dashboard.error()).toBe(false);
  });
});

describe('dashboard refresh ownership', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  for (const oldFails of [false, true]) {
    for (const newestFails of [false, true]) {
      it.each([false, true])(
        `ignores superseded ${oldFails ? 'failure' : 'success'} with newest ${newestFails ? 'failure' : 'success'} (newest completes first: %s)`,
        (newestFirst) => {
          const dashboard = TestBed.inject(DashboardService);
          const http = TestBed.inject(HttpTestingController);
          const newestData = { ...empty, groupCount: 2 };
          dashboard.refresh();
          http.expectOne('/api/dashboard').flush(empty);
          dashboard.refresh();
          const oldRequest = http.expectOne('/api/dashboard');
          dashboard.refresh();
          const newestRequest = http.expectOne('/api/dashboard');
          const finishOld = () => {
            if (oldFails) {
              oldRequest.flush({}, { status: 503, statusText: 'Unavailable' });
            } else {
              oldRequest.flush({ ...empty, groupCount: 1 });
            }
          };
          const finishNewest = () => {
            if (newestFails) {
              newestRequest.flush({}, { status: 503, statusText: 'Unavailable' });
            } else {
              newestRequest.flush(newestData);
            }
          };
          const expectNewestState = () => {
            expect(dashboard.data()).toEqual(newestFails ? empty : newestData);
            expect(dashboard.error()).toBe(newestFails);
            expect(dashboard.loading()).toBe(false);
          };

          if (newestFirst) {
            finishNewest();
            expectNewestState();
            finishOld();
          } else {
            finishOld();
            expect(dashboard.data()).toEqual(empty);
            expect(dashboard.error()).toBe(false);
            expect(dashboard.loading()).toBe(true);
            finishNewest();
          }
          expectNewestState();
        },
      );
    }
  }
});
