import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { storeTestSession } from '../core/auth/auth-test-fixture';
import { GroupDetailComponent } from './group-detail.component';
const me = { id: 'account', displayName: 'Alex', email: 'alex@test.com' };
const primary = { ...me, id: 'primary', canonicalParticipantId: 'primary', accountId: me.id };
const alias = {
  id: 'alias',
  displayName: 'Former guest',
  email: me.email,
  canonicalParticipantId: 'primary',
  accountId: me.id,
};
const guest = {
  id: 'guest',
  displayName: 'Guest Pat',
  email: null,
  canonicalParticipantId: 'guest',
  accountId: null,
};
describe('participant roster and attributed financial history', () => {
  it('labels owned original payer aliases as You and displays active guests without changing original detail rows', () => {
    TestBed.configureTestingModule({
      imports: [GroupDetailComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: 'g' })) } },
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    storeTestSession(me);
    const fixture = TestBed.createComponent(GroupDetailComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/g').flush({
      id: 'g',
      name: 'Trip',
      type: 'TRIP',
      createdBy: me,
      members: [me],
      participants: [primary, guest],
      ownedParticipantIds: ['primary', 'alias'],
      viewerParticipantId: 'primary',
      simplifyDebts: true,
    });
    http.expectOne('/api/groups/g/balances/mine').flush([]);
    http.expectOne('/api/groups/g/balances').flush({ balances: [] });
    const summary = {
      id: 'e',
      description: 'Original lunch',
      paidBy: alias,
      amountMinor: 100,
      currency: 'USD',
      category: 'OTHER',
      spentOn: '2026-10-26',
      viewerDeltaMinor: 0,
      participantCount: 2,
    };
    http.expectOne('/api/groups/g/expenses').flush([summary]);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('.gd__sub')?.textContent).toContain('Guest');
    expect(root.textContent).toContain('You paid $1.00');
    root.querySelector<HTMLButtonElement>('.xrow')!.click();
    http.expectOne('/api/groups/g/expenses/e').flush({
      ...summary,
      version: 0,
      groupId: 'g',
      splitType: 'EQUAL',
      participants: [
        { user: primary, shareMinor: 50 },
        { user: alias, shareMinor: 50 },
      ],
    });
    fixture.detectChanges();
    expect(root.querySelectorAll('.xdetail__line:not(.xdetail__line--paid)')).toHaveLength(2);
    expect(root.textContent).toContain('same account');
    http.verify();
    TestBed.resetTestingModule();
  });
});
