import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { NavigationEnd, provideRouter, Router } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';
import { InvitationComponent } from './invitation.component';
const me = { id: 'account', displayName: 'Alex', email: 'alex@test.com' };
const token = 'A'.repeat(43);
describe('explicit invitation review and acceptance', () => {
  let http: HttpTestingController;
  const user = signal<typeof me | null>(null);
  beforeEach(() => {
    user.set(null);
    sessionStorage.clear();
    localStorage.clear();
    history.replaceState({}, '', '/invitation#action=group-invitation&token=' + token);
    TestBed.configureTestingModule({
      imports: [InvitationComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: { user } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    http.verify({ ignoreCancelled: true });
    TestBed.resetTestingModule();
    history.replaceState({}, '', '/');
  });
  function button(root: HTMLElement, label: string) {
    return [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === label,
    )!;
  }
  it('immediately scrubs secrets while signed out and requires reopening after sign-in', () => {
    const fixture = TestBed.createComponent(InvitationComponent);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(location.hash).toBe('');
    expect(root.textContent).toContain('reopen the original invitation');
    expect(root.textContent).not.toContain(token);
    expect(sessionStorage.length).toBe(0);
    expect(localStorage.length).toBe(0);
    http.expectNone((r) => r.url.includes('/invitations'));
    user.set(me);
    TestBed.inject(SessionService).reset();
    fixture.detectChanges();
    expect(root.textContent).toContain('Reopen');
    expect(button(root, 'Preview invitation')).toBeUndefined();
    history.replaceState({}, '', '/invitation#action=group-invitation&token=' + token);
    window.dispatchEvent(new HashChangeEvent('hashchange'));
    fixture.detectChanges();
    expect(location.hash).toBe('');
    expect(button(root, 'Preview invitation')).toBeDefined();
    history.replaceState({}, '', '/invitation#action=group-invitation&token=' + token);
    (TestBed.inject(Router).events as Subject<unknown>).next(
      new NavigationEnd(2, '/invitation', '/invitation'),
    );
    expect(location.hash).toBe('');
  });
  it('previews per-currency combined ownership and requires explicit irreversible confirmation before claiming', () => {
    user.set(me);
    const fixture = TestBed.createComponent(InvitationComponent);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    button(root, 'Preview invitation').click();
    const read = http.expectOne('/api/invitations/preview');
    expect(read.request.body).toEqual({ token });
    read.flush({
      invitationId: 'i',
      kind: 'EMAIL',
      groupId: 'g',
      groupName: 'Trip',
      expiresAt: '2026-10-27T00:00:00Z',
      participant: {
        id: 'guest',
        displayName: 'Pat',
        email: null,
        accountId: null,
        canonicalParticipantId: 'guest',
      },
      requiresOwnershipConfirmation: true,
      balances: [
        {
          currency: 'KWD',
          existingNetMinor: 500,
          participantNetMinor: -1000,
          resultingNetMinor: -500,
        },
        { currency: 'JPY', existingNetMinor: 0, participantNetMinor: 0, resultingNetMinor: 0 },
      ],
    });
    fixture.detectChanges();
    expect(root.textContent).toContain('cannot be undone');
    expect(root.textContent).toContain('KWD');
    expect(root.textContent).toContain('JPY');
    expect(root.textContent).not.toContain('settled');
    expect(button(root, 'Claim guest and join').disabled).toBe(true);
    http.expectNone('/api/invitations/accept');
    const confirm = root.querySelector<HTMLInputElement>('#confirm-ownership')!;
    confirm.click();
    fixture.detectChanges();
    button(root, 'Claim guest and join').click();
    const accept = http.expectOne('/api/invitations/accept');
    expect(accept.request.body).toEqual({ token, confirmOwnership: true });
    accept.flush({ groupId: 'g', participantId: 'guest', accountId: me.id, claimed: true });
    http.expectOne('/api/dashboard').flush({});
    fixture.detectChanges();
    expect(root.textContent).toContain('Ownership accepted');
    expect(button(root, 'Claim guest and join')).toBeUndefined();
  });

  it('blocks uncertain claim reposts and checks original guest ownership instead of trusting existing membership', () => {
    user.set(me);
    const fixture = TestBed.createComponent(InvitationComponent);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    button(root, 'Preview invitation').click();
    http.expectOne('/api/invitations/preview').flush({
      invitationId: 'i',
      kind: 'EMAIL',
      groupId: 'g',
      groupName: 'Trip',
      participant: { id: 'guest', displayName: 'Pat', email: null },
      requiresOwnershipConfirmation: true,
      balances: [],
    });
    fixture.detectChanges();
    root.querySelector<HTMLInputElement>('#confirm-ownership')!.click();
    fixture.detectChanges();
    button(root, 'Claim guest and join').click();
    http.expectOne('/api/invitations/accept').flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(button(root, 'Claim guest and join').disabled).toBe(true);
    button(root, 'Read current group status').click();
    http.expectOne('/api/groups/g').flush({
      id: 'g',
      members: [me],
      participants: [],
      ownedParticipantIds: ['primary'],
      viewerParticipantId: 'primary',
    });
    fixture.detectChanges();
    expect(root.textContent).toContain('membership exists');
    expect(root.textContent).toContain('guest ownership is not confirmed');
    http.expectNone('/api/invitations/accept');
    window.dispatchEvent(new Event('focus'));
    fixture.detectChanges();
    expect(root.textContent).toContain('guest ownership is not confirmed');
    http.expectNone('/api/invitations/accept');
    button(root, 'Read current group status').click();
    http.expectOne('/api/groups/g').flush({
      id: 'g',
      members: [me],
      participants: [],
      ownedParticipantIds: ['primary', 'guest'],
      viewerParticipantId: 'primary',
    });
    http.expectOne('/api/dashboard').flush({});
    fixture.detectChanges();
    expect(root.textContent).toContain('Ownership confirmed');
    expect(button(root, 'Claim guest and join')).toBeUndefined();
  });

  it('joins a group link explicitly with both wire capabilities and never sends guest confirmation', () => {
    user.set(me);
    const fixture = TestBed.createComponent(InvitationComponent);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    button(root, 'Preview invitation').click();
    const preview = http.expectOne('/api/invitations/preview');
    expect(preview.request.headers.get('X-Ledger-Version')).toBe('2');
    expect(preview.request.headers.get('X-Participant-Version')).toBe('1');
    preview.flush({
      invitationId: 'i',
      kind: 'LINK',
      groupId: 'g',
      groupName: 'Trip',
      participant: null,
      requiresOwnershipConfirmation: false,
      balances: [],
    });
    fixture.detectChanges();
    expect(root.querySelector('#confirm-ownership')).toBeNull();
    http.expectNone('/api/invitations/accept');
    button(root, 'Join group').click();
    const accept = http.expectOne('/api/invitations/accept');
    expect(accept.request.body).toEqual({ token });
    expect(accept.request.headers.get('X-Participant-Version')).toBe('1');
    accept.flush({ groupId: 'g', participantId: 'primary', accountId: me.id, claimed: false });
    const dashboard = http.expectOne('/api/dashboard');
    expect(dashboard.request.headers.get('X-Ledger-Version')).toBe('2');
    expect(dashboard.request.headers.get('X-Participant-Version')).toBe('1');
    dashboard.flush({});
    fixture.detectChanges();
    expect(root.textContent).toContain('No guest entries were claimed');
  });
});
