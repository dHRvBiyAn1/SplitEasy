import { storeTestSession } from '../core/auth/auth-test-fixture';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { GroupSettingsComponent } from './group-settings.component';
import { DashboardService } from '../dashboard/dashboard.service';

const alice = { id: 'alice', displayName: 'Alice', email: 'alice@example.test' };
const bob = { id: 'bob', displayName: 'Bob', email: 'bob@example.test' };
const carol = { id: 'carol', displayName: 'Carol', email: 'carol@example.test' };
const dan = { id: 'dan', displayName: 'Dan', email: 'dan@example.test' };
const group = {
  id: 'g',
  name: 'Obligations',
  type: 'HOME',
  createdBy: alice,
  members: [alice, bob, carol, dan],
  simplifyDebts: true,
  createdAt: '',
};

// Approved seams: rendered settings controls and real Angular services at the HTTP boundary.
describe('group settings bilateral obligations', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    localStorage.clear();
    storeTestSession(bob);
    TestBed.configureTestingModule({
      imports: [GroupSettingsComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: 'g' })) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    http.verify({ ignoreCancelled: true });
    localStorage.clear();
  });

  it.each([
    { role: 'self', viewer: bob, creator: alice, simplifyDebts: false },
    { role: 'other member', viewer: alice, creator: alice, simplifyDebts: false },
    { role: 'creator', viewer: bob, creator: bob, simplifyDebts: false },
    { role: 'self without simplification', viewer: bob, creator: alice, simplifyDebts: false },
  ])(
    'blocks net-zero $role with both counterparties visible',
    ({ viewer, creator, simplifyDebts }) => {
      storeTestSession(viewer);
      const fixture = TestBed.createComponent(GroupSettingsComponent);
      fixture.detectChanges();
      http.expectOne('/api/groups/g').flush({ ...group, createdBy: creator, simplifyDebts });
      http.expectOne('/api/groups/g/balances').flush({
        groupId: 'g',
        balances: [
          { user: alice, netCents: -1000 },
          { user: bob, netCents: 0 },
          { user: carol, netCents: 1000 },
          { user: dan, netCents: 0 },
        ],
      });
      // Matching, rather than requiring, lets the old screen reproduce its unsafe enabled controls.
      http.match('/api/groups/g/balances/current-obligations').forEach((r) =>
        r.flush([
          { from: alice, to: bob, amountCents: 1000 },
          { from: bob, to: carol, amountCents: 1000 },
        ]),
      );
      fixture.detectChanges();
      const root: HTMLElement = fixture.nativeElement;
      const remove = root.querySelector<HTMLButtonElement>('[aria-label="Remove Bob"]')!;
      expect(remove.disabled).toBe(true);
      expect(remove.closest('.mrow')!.textContent).not.toContain('settled');
      expect(root.textContent).toContain('Is owed $10.00 by Alice (alice@example.test)');
      expect(root.textContent).toContain('Owes $10.00 to Carol (carol@example.test)');
      const leave = Array.from(root.querySelectorAll('button')).find(
        (b) => b.textContent?.trim() === 'Leave group',
      )!;
      expect(leave.disabled).toBe(true);
      expect(root.querySelector<HTMLButtonElement>('[aria-label="Remove Dan"]')!.disabled).toBe(
        false,
      );
    },
  );

  it('lets the simplified zero-net intermediary leave using current-mode obligations', () => {
    const fixture = TestBed.createComponent(GroupSettingsComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/g').flush(group);
    http.expectOne('/api/groups/g/balances').flush({
      balances: [
        { user: alice, netCents: -1000 },
        { user: bob, netCents: 0 },
        { user: carol, netCents: 1000 },
        { user: dan, netCents: 0 },
      ],
    });
    // Old code consumes raw debts and wrongly blocks Bob. The canonical read omits him.
    http.match('/api/groups/g/balances/obligations').forEach((r) =>
      r.flush([
        { from: alice, to: bob, amountCents: 1000 },
        { from: bob, to: carol, amountCents: 1000 },
      ]),
    );
    http
      .match('/api/groups/g/balances/current-obligations')
      .forEach((r) => r.flush([{ from: alice, to: carol, amountCents: 1000 }]));
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector<HTMLButtonElement>('[aria-label="Remove Bob"]')!.disabled).toBe(
      false,
    );
    const leave = Array.from(root.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === 'Leave group',
    )!;
    expect(leave.disabled).toBe(false);
    expect(root.textContent).toContain('Simplified obligations');
    leave.click();
    fixture.detectChanges();
    Array.from(root.querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === 'Yes, leave')!
      .click();
    const request = http.expectOne('/api/groups/g/members/bob');
    expect(request.request.method).toBe('DELETE');
    request.flush({ message: 'Reload' }, { status: 409, statusText: 'Conflict' });
  });

  it('permits settled members to be removed and to leave, while protecting the creator', () => {
    const fixture = TestBed.createComponent(GroupSettingsComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/g').flush(group);
    http
      .expectOne('/api/groups/g/balances')
      .flush({ balances: group.members.map((user) => ({ user, netCents: 0 })) });
    http.expectOne('/api/groups/g/balances/current-obligations').flush([]);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector<HTMLButtonElement>('[aria-label="Remove Alice"]')!.disabled).toBe(
      true,
    );
    expect(root.querySelector<HTMLButtonElement>('[aria-label="Remove Dan"]')!.disabled).toBe(
      false,
    );
    const leave = Array.from(root.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === 'Leave group',
    )!;
    expect(leave.disabled).toBe(false);
    leave.click();
    fixture.detectChanges();
    Array.from(root.querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === 'Yes, leave')!
      .click();
    const request = http.expectOne('/api/groups/g/members/bob');
    expect(request.request.method).toBe('DELETE');
    request.flush(
      { message: 'Membership changed; reload.' },
      { status: 409, statusText: 'Conflict' },
    );
  });

  it('fails closed after a refresh cannot load obligations, including an open leave confirmation', () => {
    const fixture = TestBed.createComponent(GroupSettingsComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/g').flush(group);
    http
      .expectOne('/api/groups/g/balances')
      .flush({ balances: group.members.map((user) => ({ user, netCents: 0 })) });
    http.expectOne('/api/groups/g/balances/current-obligations').flush([]);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    Array.from(root.querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === 'Leave group')!
      .click();
    fixture.detectChanges();
    TestBed.inject(DashboardService).refresh();
    http
      .expectOne('/api/dashboard')
      .flush({ groups: [], people: [], settlements: [], activity: [] });
    fixture.detectChanges();
    http.expectOne('/api/groups/g').flush(group);
    http.expectOne('/api/groups/g/balances').flush({ balances: [] });
    const obligations = http.expectOne('/api/groups/g/balances/current-obligations');
    fixture.detectChanges();
    expect(root.querySelector('[role="status"]')).not.toBeNull();
    expect(root.textContent).not.toContain('settled');
    obligations.flush('unavailable', { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(root.textContent).toContain('Could not load this group, its balances and obligations.');
    expect(root.textContent).not.toContain('settled');
    expect(root.querySelector<HTMLButtonElement>('[aria-label="Remove Bob"]')!.disabled).toBe(true);
    expect(root.querySelector<HTMLButtonElement>('[aria-label="Remove Dan"]')!.disabled).toBe(true);
    const confirm = Array.from(root.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === 'Yes, leave',
    )!;
    expect(confirm.disabled).toBe(true);
    confirm.click();
    http.expectNone((r) => r.method === 'DELETE');
  });
});
