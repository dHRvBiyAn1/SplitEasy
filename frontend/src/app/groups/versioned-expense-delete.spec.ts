import { storeTestSession } from '../core/auth/auth-test-fixture';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { GroupDetailComponent } from './group-detail.component';

const user = { id: 'me', displayName: 'Owner', email: 'owner@example.test' };

describe('versioned expense deletion through the group route', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('sends the displayed version and retains the expense after a stale-delete conflict', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'groups/:id', component: GroupDetailComponent }]),
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    storeTestSession(user);
    const harness = await RouterTestingHarness.create('/groups/g');
    const summary = {
      id: 'e',
      version: 3,
      description: 'Dinner',
      amountCents: 1200,
      paidBy: user,
      participantCount: 1,
      category: 'OTHER',
      spentOn: '2026-10-18',
      viewerDeltaCents: 0,
    };
    http.expectOne('/api/groups/g').flush({
      id: 'g',
      name: 'Trip',
      type: 'HOME',
      createdBy: user,
      members: [user],
      simplifyDebts: true,
    });
    http.expectOne('/api/groups/g/expenses').flush([summary]);
    http.expectOne('/api/groups/g/balances/mine').flush([]);
    harness.detectChanges();
    (harness.routeNativeElement!.querySelector('.xrow') as HTMLButtonElement).click();
    http.expectOne('/api/groups/g/expenses/e').flush({
      ...summary,
      groupId: 'g',
      splitType: 'EQUAL',
      participants: [{ user, shareCents: 1200 }],
    });
    harness.detectChanges();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    (harness.routeNativeElement!.querySelector('.xdetail__del') as HTMLButtonElement).click();
    const deletion = http.expectOne((request) => request.method === 'DELETE');
    expect(deletion.request.urlWithParams).toBe('/api/groups/g/expenses/e?expectedVersion=3');
    deletion.flush(
      { message: 'This expense changed. Reload and review the latest changes.' },
      { status: 409, statusText: 'Conflict' },
    );
    harness.detectChanges();
    expect(harness.routeNativeElement!.textContent).toContain('Dinner');
    expect(harness.routeNativeElement!.textContent).toContain('Reload and review');
    http.expectNone((request) => request.method === 'DELETE');
    http.verify();
  });
});
