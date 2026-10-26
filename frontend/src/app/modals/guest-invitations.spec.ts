import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AddMemberModalComponent } from './add-member-modal.component';
import { SessionService } from '../core/auth/session.service';
import { ModalService } from './modal.service';
const member = { id: 'account', displayName: 'Alex', email: 'alex@test.com' };
const guest = {
  id: 'guest',
  displayName: 'Pat',
  email: null,
  canonicalParticipantId: 'guest',
  accountId: null,
};
const group = {
  id: 'g',
  name: 'Trip',
  members: [member],
  participants: [{ ...member, canonicalParticipantId: member.id, accountId: member.id }],
  viewerParticipantId: member.id,
  ownedParticipantIds: [member.id],
};
describe('guest roster and invitation management', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AddMemberModalComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(ModalService).openAddMember('g');
  });
  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
  });
  function open() {
    const fixture = TestBed.createComponent(AddMemberModalComponent);
    http.expectOne('/api/groups/g').flush(group);
    fixture.detectChanges();
    return fixture;
  }
  function button(root: HTMLElement, label: string) {
    return [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === label,
    )!;
  }
  function input(root: HTMLElement, selector: string, value: string) {
    const el = root.querySelector<HTMLInputElement>(selector)!;
    expect(el).not.toBeNull();
    el.value = value;
    el.dispatchEvent(new Event('input'));
  }
  it('creates a guest without account access or email delivery and safely retries the original operation', () => {
    const fixture = open();
    const root: HTMLElement = fixture.nativeElement;
    input(root, '#guest-name', 'Pat');
    fixture.detectChanges();
    button(root, 'Create guest').click();
    const create = http.expectOne('/api/groups/g/participants');
    expect(create.request.body.displayName).toBe('Pat');
    const original = create.request.body;
    expect(original.operationId).toBeTruthy();
    create.flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(root.textContent).toContain('may already');
    button(root, 'Retry guest creation').click();
    const retry = http.expectOne('/api/groups/g/participants');
    expect(retry.request.body).toEqual(original);
    retry.flush(guest);
    http.expectOne('/api/dashboard').flush({});
    fixture.detectChanges();
    expect(root.textContent).toContain('Guest created');
    expect(root.querySelector('.chips')?.textContent).not.toContain('Pat');
    http.expectNone((r) => r.url.includes('/invitations'));
  });
  it('shows guest removal conflicts without pretending account membership changed', () => {
    const fixture = TestBed.createComponent(AddMemberModalComponent);
    http
      .expectOne('/api/groups/g')
      .flush({ ...group, participants: [...group.participants, guest] });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    button(root, 'Remove guest Pat').click();
    const remove = http.expectOne('/api/groups/g/participants/guest');
    expect(remove.request.method).toBe('DELETE');
    remove.flush(
      { message: 'Settle every currency and direct obligation first.' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    expect(root.textContent).toContain('Settle every currency');
    expect(root.textContent).toContain('Pat');
    http.expectNone('/api/groups/g/members/guest');
  });

  it('keeps disabled mail honest and sends a distinct pending invitation to an exact guest', () => {
    const fixture = TestBed.createComponent(AddMemberModalComponent);
    http
      .expectOne('/api/groups/g')
      .flush({ ...group, participants: [...group.participants, guest] });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    button(root, 'Manage invitations').click();
    http.expectOne('/api/groups/g/invitations').flush([]);
    fixture.detectChanges();
    input(root, '#invite-email', 'pat@test.com');
    const select = root.querySelector<HTMLSelectElement>('#invite-participant')!;
    select.value = 'guest';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    button(root, 'Send email invitation').click();
    const send = http.expectOne('/api/groups/g/invitations/email');
    expect(send.request.body).toMatchObject({ email: 'pat@test.com', participantId: 'guest' });
    const original = send.request.body;
    send.flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(root.textContent).toContain('Email delivery unavailable');
    expect(root.textContent).not.toContain('Invitation sent');
    button(root, 'Retry original email invitation').click();
    const retry = http.expectOne('/api/groups/g/invitations/email');
    expect(retry.request.body).toEqual(original);
    retry.flush({
      id: 'i',
      kind: 'EMAIL',
      participantId: 'guest',
      participantName: 'Pat',
      status: 'PENDING',
      createdAt: '2026-10-26T00:00:00Z',
      expiresAt: '2026-10-27T00:00:00Z',
      acceptedAt: null,
    });
    fixture.detectChanges();
    expect(root.textContent).toContain('Invitation sent');
    expect(root.textContent).toContain('Pending');
    expect(root.textContent).toContain('Email invitation');
    expect(root.textContent).not.toContain('2026-10-27T00:00:00Z');
    expect(root.querySelector('.chips')?.textContent).not.toContain('Pat');
    http.expectNone('/api/groups/g/members');
  });

  it('resolves a one-time join link against this origin, revokes pending status, and clears its secret on session change', () => {
    const fixture = open();
    const root: HTMLElement = fixture.nativeElement;
    button(root, 'Manage invitations').click();
    http.expectOne('/api/groups/g/invitations').flush([]);
    fixture.detectChanges();
    button(root, 'Generate join link').click();
    const create = http.expectOne('/api/groups/g/invitations/links');
    expect(create.request.body).toEqual({});
    create.flush({
      invitation: {
        id: 'link',
        kind: 'LINK',
        status: 'PENDING',
        expiresAt: '2026-10-27T00:00:00Z',
      },
      url: '/invitation#action=group-invitation&token=' + 'A'.repeat(43),
    });
    fixture.detectChanges();
    expect(root.querySelector<HTMLInputElement>('#join-link')!.value).toBe(
      location.origin + '/invitation#action=group-invitation&token=' + 'A'.repeat(43),
    );
    button(root, 'Revoke invitation').click();
    http.expectOne('/api/groups/g/invitations/link').flush(null);
    fixture.detectChanges();
    expect(root.querySelector('#join-link')).toBeNull();
    expect(root.textContent).toContain('Revoked');
    button(root, 'Generate join link').click();
    http.expectOne('/api/groups/g/invitations/links').flush({
      invitation: { id: 'link2', kind: 'LINK', status: 'PENDING' },
      url: '/invitation#action=group-invitation&token=' + 'B'.repeat(43),
    });
    fixture.detectChanges();
    TestBed.inject(SessionService).reset();
    fixture.detectChanges();
    expect(root.querySelector('#join-link')).toBeNull();
    expect(root.textContent).not.toContain('BBBB');
  });

  it('requires status review and revocation after a lost link response before deliberate replacement', () => {
    const fixture = open();
    const root: HTMLElement = fixture.nativeElement;
    button(root, 'Manage invitations').click();
    http.expectOne('/api/groups/g/invitations').flush([]);
    fixture.detectChanges();
    button(root, 'Generate join link').click();
    http
      .expectOne('/api/groups/g/invitations/links')
      .flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(button(root, 'Generate join link').disabled).toBe(true);
    expect(button(root, 'Create replacement link').disabled).toBe(true);
    button(root, 'Read invitation status').click();
    http
      .expectOne('/api/groups/g/invitations')
      .flush([{ id: 'lost', kind: 'LINK', status: 'PENDING' }]);
    fixture.detectChanges();
    expect(button(root, 'Create replacement link').disabled).toBe(true);
    expect(root.textContent).toContain('may already exist');
    http.expectNone('/api/groups/g/invitations/links');
    button(root, 'Revoke invitation').click();
    http.expectOne('/api/groups/g/invitations/lost').flush(null);
    fixture.detectChanges();
    expect(button(root, 'Create replacement link').disabled).toBe(false);
    button(root, 'Create replacement link').click();
    http.expectOne('/api/groups/g/invitations/links').flush({
      invitation: { id: 'new', kind: 'LINK', status: 'PENDING' },
      url: '/invitation#action=group-invitation&token=' + 'C'.repeat(43),
    });
    fixture.detectChanges();
    expect(root.querySelector<HTMLInputElement>('#join-link')!.value).toContain('CCC');
  });

  it('resends deliberately with a new operation only after reviewing an uncertain email', () => {
    const fixture = open();
    const root: HTMLElement = fixture.nativeElement;
    button(root, 'Manage invitations').click();
    http.expectOne('/api/groups/g/invitations').flush([]);
    fixture.detectChanges();
    input(root, '#invite-email', 'old@test.com');
    fixture.detectChanges();
    button(root, 'Send email invitation').click();
    const first = http.expectOne('/api/groups/g/invitations/email');
    const operation = first.request.body.operationId;
    first.flush({ message: 'Review pending invitation' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    button(root, 'Read invitation status').click();
    http
      .expectOne('/api/groups/g/invitations')
      .flush([{ id: 'old', kind: 'EMAIL', status: 'PENDING' }]);
    fixture.detectChanges();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    button(root, 'Review a separate email invitation').click();
    input(root, '#invite-email', 'new@test.com');
    fixture.detectChanges();
    button(root, 'Resend and invalidate earlier email link').click();
    const resend = http.expectOne('/api/groups/g/invitations/email');
    expect(resend.request.body).toMatchObject({ email: 'new@test.com', resend: true });
    expect(resend.request.body.operationId).not.toBe(operation);
    resend.flush({ id: 'new', kind: 'EMAIL', status: 'PENDING' });
  });

  it('re-adds a removed guest with the same identity for historical repair without reviving old invitations', () => {
    const fixture = TestBed.createComponent(AddMemberModalComponent);
    http
      .expectOne('/api/groups/g')
      .flush({ ...group, participants: [...group.participants, guest], inactiveGuests: [] });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    button(root, 'Manage invitations').click();
    http.expectOne('/api/groups/g/invitations').flush([
      {
        id: 'old',
        kind: 'EMAIL',
        status: 'PENDING',
        participantId: 'guest',
        participantName: 'Pat',
      },
    ]);
    fixture.detectChanges();
    button(root, 'Remove guest Pat').click();
    http.expectOne('/api/groups/g/participants/guest').flush(null);
    http.expectOne('/api/dashboard').flush({});
    fixture.detectChanges();
    expect(root.textContent).toContain('Removed guests');
    expect(root.textContent).toContain('Revoked');
    button(root, 'Re-add guest Pat').click();
    const readd = http.expectOne('/api/groups/g/participants/guest/reactivate');
    expect(readd.request.method).toBe('POST');
    readd.flush(guest);
    http.expectOne('/api/dashboard').flush({});
    fixture.detectChanges();
    expect(button(root, 'Remove guest Pat')).toBeDefined();
    expect(button(root, 'Re-add guest Pat')).toBeUndefined();
    expect(root.textContent).toContain('Revoked');
    http.expectNone('/api/groups/g/participants');
    http.expectNone('/api/groups/g/invitations/email');
  });
});
