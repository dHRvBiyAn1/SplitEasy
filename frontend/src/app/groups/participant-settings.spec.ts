import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { storeTestSession } from '../core/auth/auth-test-fixture';
import { GroupSettingsComponent } from './group-settings.component';
const me = { id: 'account', displayName: 'Alex', email: 'alex@test.com' };
const creator = { id: 'creator-account', displayName: 'Creator', email: 'creator@test.com' };
const primary = { ...me, id: 'primary', canonicalParticipantId: 'primary', accountId: me.id };
const other = {
  ...creator,
  id: 'creator-primary',
  canonicalParticipantId: 'creator-primary',
  accountId: creator.id,
};
const guest = {
  id: 'guest',
  displayName: 'Pat',
  email: null,
  canonicalParticipantId: 'guest',
  accountId: null,
};
describe('account access and financial ownership in settings', () => {
  it('blocks departure by owned financial obligations while retaining account creator protections and removal IDs', () => {
    TestBed.configureTestingModule({
      imports: [GroupSettingsComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: 'g' })) } },
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    storeTestSession(me);
    const fixture = TestBed.createComponent(GroupSettingsComponent);
    fixture.detectChanges();
    const load = (debt: boolean) => {
      http.expectOne('/api/groups/g').flush({
        id: 'g',
        name: 'Trip',
        type: 'TRIP',
        createdBy: creator,
        members: [me, creator],
        participants: [primary, other, guest],
        viewerParticipantId: 'primary',
        ownedParticipantIds: ['primary', 'alias'],
        simplifyDebts: false,
      });
      http
        .expectOne('/api/groups/g/balances')
        .flush({ balances: [{ user: primary, netMinor: 0, currency: 'USD' }] });
      http
        .expectOne('/api/groups/g/balances/current-obligations')
        .flush(debt ? [{ from: primary, to: guest, amountMinor: 100, currency: 'USD' }] : []);
      fixture.detectChanges();
    };
    load(true);
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector<HTMLButtonElement>('[aria-label="Remove Alex"]')!.disabled).toBe(
      true,
    );
    expect(root.querySelector<HTMLButtonElement>('[aria-label="Remove Creator"]')!.disabled).toBe(
      true,
    );
    expect(root.textContent).toContain('Owes $1.00 to Pat (guest)');
    const button = (label: string) =>
      [...root.querySelectorAll<HTMLButtonElement>('button')].find(
        (b) => b.textContent?.trim() === label,
      )!;
    expect(button('Leave group').disabled).toBe(true);
    fixture.componentInstance.retry();
    load(false);
    button('Leave group').click();
    fixture.detectChanges();
    button('Yes, leave').click();
    const remove = http.expectOne('/api/groups/g/members/account');
    expect(remove.request.method).toBe('DELETE');
    remove.flush({}, { status: 409, statusText: 'Conflict' });
    http.verify();
    TestBed.resetTestingModule();
  });
});
