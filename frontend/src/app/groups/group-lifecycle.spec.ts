import { storeTestSession } from '../core/auth/auth-test-fixture';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { GroupSettingsComponent } from './group-settings.component';
import { DeletedGroupsComponent } from './deleted-groups.component';

const alice = { id: 'alice', displayName: 'Alice', email: 'alice@example.test' };
const bob = { id: 'bob', displayName: 'Bob', email: 'bob@example.test' };
const group = {
  id: 'g',
  name: 'Trip',
  type: 'TRIP',
  createdBy: alice,
  members: [alice, bob],
  simplifyDebts: true,
  createdAt: '',
  archivedAt: null as string | null,
  lifecycleVersion: 0,
};

function button(root: HTMLElement, label: string): HTMLButtonElement | undefined {
  return Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.trim() === label);
}

/** Approved seams: rendered settings/recovery controls with real services at the HTTP boundary. */
describe('G03 group lifecycle controls', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [GroupSettingsComponent, DeletedGroupsComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: 'g' })) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    storeTestSession(alice);
  });

  afterEach(() => {
    http.verify({ ignoreCancelled: true });
    localStorage.clear();
  });

  const overview = { totals: [], groups: [], people: [], settlements: [], activity: [] };

  /**
   * Every settings mutation calls dashboard.refresh(), which re-reads the overview and this
   * group. Answering that cascade keeps the assertions about the reloaded state, not a spinner.
   */
  function drain(state: Partial<typeof group> = {}) {
    for (const request of http.match(() => true)) {
      const url = request.request.url;
      if (url === '/api/dashboard') request.flush(overview);
      else if (url === '/api/groups/g') request.flush({ ...group, ...state });
      else if (url === '/api/groups/g/balances')
        request.flush({ groupId: 'g', balances: [{ user: alice, netMinor: 0 }] });
      else if (url === '/api/groups/g/balances/current-obligations') request.flush([]);
      else request.flush(null);
    }
  }

  function openSettings(overrides: Partial<typeof group> = {}) {
    const fixture = TestBed.createComponent(GroupSettingsComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/g').flush({ ...group, ...overrides });
    http
      .expectOne('/api/groups/g/balances')
      .flush({ groupId: 'g', balances: [{ user: alice, netMinor: 0 }] });
    http.expectOne('/api/groups/g/balances/current-obligations').flush([]);
    fixture.detectChanges();
    return fixture;
  }

  it('archives an open group and re-opens an archived one through the lifecycle routes', () => {
    const fixture = openSettings();
    const root: HTMLElement = fixture.nativeElement;

    button(root, 'Archive group')!.click();
    const archive = http.expectOne('/api/groups/g/archive');
    expect(archive.request.method).toBe('POST');
    archive.flush({ ...group, archivedAt: '2026-11-10T10:00:00Z', lifecycleVersion: 1 });
    drain({ archivedAt: '2026-11-10T10:00:00Z', lifecycleVersion: 1 });
    fixture.detectChanges();

    expect(root.textContent).toContain('This group is archived');
    expect(button(root, 'Archive group')).toBeUndefined();

    button(root, 'Re-open group')!.click();
    const unarchive = http.expectOne('/api/groups/g/unarchive');
    expect(unarchive.request.method).toBe('POST');
    unarchive.flush({ ...group, archivedAt: null, lifecycleVersion: 2 });
    drain({ archivedAt: null, lifecycleVersion: 2 });
    fixture.detectChanges();

    expect(button(root, 'Archive group')).toBeDefined();
    expect(root.textContent).not.toContain('This group is archived');
  });

  it('disables entry actions while archived and keeps settings editable', () => {
    const fixture = openSettings({ archivedAt: '2026-11-10T10:00:00Z', lifecycleVersion: 1 });
    const root: HTMLElement = fixture.nativeElement;

    // Adding a member is a new entry, so it must not be offered on a read-only group.
    const addMember = root.querySelector<HTMLButtonElement>('.mrow--add');
    expect(addMember?.disabled).toBe(true);

    // Renaming and re-typing stay available: they are not financial entries.
    expect(root.querySelector<HTMLButtonElement>('[aria-label="Rename group"]')!.disabled).toBe(
      false,
    );
    expect(
      root.querySelector<HTMLButtonElement>('[aria-label="Group type Dining"]')!.disabled,
    ).toBe(false);
  });

  it('changes the group type through the existing PATCH path', () => {
    const fixture = openSettings();
    const root: HTMLElement = fixture.nativeElement;

    root.querySelector<HTMLButtonElement>('[aria-label="Group type Home"]')!.click();
    const patch = http.expectOne('/api/groups/g');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toEqual({ type: 'HOME' });
    patch.flush({ ...group, type: 'HOME' });
    drain({ type: 'HOME' });
    fixture.detectChanges();

    expect(
      root
        .querySelector<HTMLButtonElement>('[aria-label="Group type Home"]')!
        .getAttribute('aria-pressed'),
    ).toBe('true');
  });

  it('describes deletion as recoverable and sends the creator-only delete', () => {
    const fixture = openSettings();
    const root: HTMLElement = fixture.nativeElement;

    expect(root.textContent).toContain('can be restored');
    expect(root.textContent).not.toContain("This can't be undone");

    button(root, 'Delete group')!.click();
    fixture.detectChanges();
    button(root, 'Yes, delete')!.click();
    const request = http.expectOne('/api/groups/g');
    expect(request.request.method).toBe('DELETE');
    request.flush(null, { status: 204, statusText: 'No Content' });
    drain();
    fixture.detectChanges();
  });

  it('keeps an unsettled delete conflict visible without leaving the group', () => {
    const fixture = openSettings();
    const root: HTMLElement = fixture.nativeElement;

    button(root, 'Delete group')!.click();
    fixture.detectChanges();
    button(root, 'Yes, delete')!.click();
    http
      .expectOne('/api/groups/g')
      .flush(
        { message: 'Settle this group before deleting it — Bob still owes Alice' },
        { status: 409, statusText: 'Conflict' },
      );
    fixture.detectChanges();

    expect(root.textContent).toContain('Settle this group before deleting it');
    expect(root.textContent).toContain('Trip');
  });

  it('restores a deleted group at its expected version from the recovery list', () => {
    const fixture = TestBed.createComponent(DeletedGroupsComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/deleted').flush([
      {
        id: 'g',
        name: 'Trip',
        type: 'TRIP',
        memberCount: 2,
        simplifyDebts: true,
        lifecycleVersion: 1,
      },
      {
        id: 'h',
        name: 'Flat',
        type: 'HOME',
        memberCount: 3,
        simplifyDebts: true,
        lifecycleVersion: 3,
      },
    ]);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.textContent).toContain('Trip');
    expect(root.textContent).toContain('Flat');

    root.querySelector<HTMLButtonElement>('[aria-label="Restore Trip"]')!.click();
    const restore = http.expectOne('/api/groups/g/restore');
    expect(restore.request.method).toBe('POST');
    expect(restore.request.body).toEqual({ expectedVersion: 1 });
    restore.flush({ ...group, lifecycleVersion: 2 });
    drain();
    fixture.detectChanges();

    // The restored group leaves the recovery list; the other deleted group stays.
    expect(root.querySelector('[aria-label="Restore Trip"]')).toBeNull();
    expect(root.textContent).toContain('Flat');
  });

  it('shows a stale restore conflict and keeps the row for a reload', () => {
    const fixture = TestBed.createComponent(DeletedGroupsComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/deleted').flush([
      {
        id: 'g',
        name: 'Trip',
        type: 'TRIP',
        memberCount: 2,
        simplifyDebts: true,
        lifecycleVersion: 1,
      },
    ]);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;

    root.querySelector<HTMLButtonElement>('[aria-label="Restore Trip"]')!.click();
    http
      .expectOne('/api/groups/g/restore')
      .flush(
        { message: 'This group changed since you loaded it — reload the recovery list' },
        { status: 409, statusText: 'Conflict' },
      );
    fixture.detectChanges();

    expect(root.textContent).toContain('This group changed since you loaded it');
    expect(root.querySelector('[aria-label="Restore Trip"]')).not.toBeNull();
  });

  it('reports an empty recovery list instead of an error', () => {
    const fixture = TestBed.createComponent(DeletedGroupsComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/deleted').flush([]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Nothing deleted');
  });
});
