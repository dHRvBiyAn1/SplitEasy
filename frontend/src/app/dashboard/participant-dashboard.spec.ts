import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { storeTestSession } from '../core/auth/auth-test-fixture';
import { DashboardComponent } from './dashboard.component';
const me = { id: 'account', displayName: 'Alex', email: 'alex@test.com' };
describe('participant-aware People and activity', () => {
  it('recognizes every explicit owned payer alias without merging guest or account names in People', () => {
    TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    const http = TestBed.inject(HttpTestingController);
    storeTestSession(me);
    const fixture = TestBed.createComponent(DashboardComponent);
    const guest = {
      id: 'guest',
      displayName: 'Pat',
      email: null,
      canonicalParticipantId: 'guest',
      accountId: null,
    };
    http.expectOne('/api/dashboard').flush({
      viewerParticipantId: 'primary',
      ownedParticipantIds: ['primary', 'alias'],
      totalNetMinor: -100,
      owedMinor: 0,
      owedPeopleCount: 0,
      oweMinor: 100,
      owePeopleCount: 1,
      groupCount: 0,
      groups: [],
      settlements: [],
      people: [{ user: guest, netMinor: -100, currency: 'USD' }],
      activity: [
        {
          id: 'e',
          kind: 'EXPENSE',
          groupId: 'g',
          groupName: 'Trip',
          description: 'Lunch',
          actor: {
            id: 'alias',
            displayName: 'Former guest',
            email: me.email,
            accountId: me.id,
            canonicalParticipantId: 'primary',
          },
          counterparty: null,
          amountMinor: 100,
          currency: 'USD',
          viewerDeltaMinor: 0,
          category: 'OTHER',
          date: '2026-10-26',
        },
      ],
    });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.textContent).toContain('You paid $1.00');
    expect(root.querySelectorAll('.friend-object')).toHaveLength(1);
    expect(root.querySelector('.friend-object')?.textContent).toContain('Pat');
    expect(root.textContent).not.toContain('(null)');
    http.verify();
    TestBed.resetTestingModule();
  });
});
