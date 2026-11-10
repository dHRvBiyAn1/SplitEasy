import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { storeTestSession } from '../core/auth/auth-test-fixture';
import { routes } from '../app.routes';

const me = { id: 'me', displayName: 'Alex', email: 'alex@test.com' };
const friend = {
  id: 'pat',
  displayName: 'Pat saved',
  createdAt: '2026-10-26T12:00:00Z',
  sharedGroupCount: 1,
  hasOpenObligations: false,
  balances: [{ currency: 'USD', netMinor: 0 }],
};
const sourceGroup = {
  id: 'trip',
  name: 'Trip',
  type: 'TRIP',
  createdBy: me,
  members: [me],
  createdAt: '2026-10-26T12:00:00Z',
  simplifyDebts: false,
  viewerParticipantId: 'me',
  ownedParticipantIds: ['me', 'my-alias'],
  participants: [
    { ...me, accountId: 'me', canonicalParticipantId: 'me' },
    {
      id: 'my-alias',
      displayName: 'My alias',
      email: null,
      accountId: 'me',
      canonicalParticipantId: 'me',
    },
    {
      id: 'pat-alias',
      displayName: 'Pat alias',
      email: null,
      accountId: 'pat',
      canonicalParticipantId: 'pat',
    },
    { id: 'pat', displayName: 'Pat', email: null, accountId: 'pat', canonicalParticipantId: 'pat' },
    {
      id: 'guest',
      displayName: 'Guest',
      email: null,
      accountId: null,
      canonicalParticipantId: 'guest',
    },
  ],
};
describe('saved friends through rendered routes and HTTP', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  beforeEach(async () => {
    sessionStorage.clear();
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter(routes)],
    });
    http = TestBed.inject(HttpTestingController);
    storeTestSession(me);
    harness = await RouterTestingHarness.create();
  });
  afterEach(() => {
    http.verify({ ignoreCancelled: true });
    TestBed.resetTestingModule();
  });
  const root = () => harness.routeNativeElement!;
  const text = () => root().textContent!;
  const button = (label: string) =>
    [...root().querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === label,
    )!;
  const select = (label: string, value: string) => {
    const field = root().querySelector<HTMLSelectElement>(`select[aria-label="${label}"]`)!;
    field.value = value;
    field.dispatchEvent(new Event('change'));
    harness.detectChanges();
  };
  it('retains settled friends and distinguishes no current shared data with both capabilities', async () => {
    await harness.navigateByUrl('/friends');
    const request = http.expectOne('/api/friends');
    expect(request.request.headers.get('X-Ledger-Version')).toBe('2');
    expect(request.request.headers.get('X-Participant-Version')).toBe('1');
    request.flush([
      friend,
      { ...friend, id: 'old', displayName: 'Old friend', sharedGroupCount: 0, balances: [] },
    ]);
    harness.detectChanges();
    expect(text()).toContain('Pat saved');
    expect(text()).toContain('All square in current shared groups');
    expect(text()).toContain('No current shared group data');
    expect(root().querySelector('a[href="/friends/pat"]')).not.toBeNull();
    expect(text()).toContain('current shared groups');
  });
  it('shows an unavailable read as an error and retries explicitly without claiming zero', async () => {
    await harness.navigateByUrl('/friends');
    http.expectOne('/api/friends').flush({}, { status: 503, statusText: 'Unavailable' });
    harness.detectChanges();
    expect(text()).toContain('Could not load saved friends');
    expect(text()).not.toContain('All square');
    button('Try again').click();
    http.expectOne('/api/friends').flush([]);
    harness.detectChanges();
    expect(text()).toContain('No saved friends yet');
  });

  it('shows even overall with open obligations and authorized group currency breakdowns', async () => {
    await harness.navigateByUrl('/friends/pat');
    http.expectOne('/api/friends/pat').flush({
      ...friend,
      hasOpenObligations: true,
      balances: [
        { currency: 'USD', netMinor: 0 },
        { currency: 'KWD', netMinor: 0 },
      ],
      sharedGroups: [
        {
          id: 'trip',
          name: 'Trip',
          simplifyDebts: false,
          balances: [
            { currency: 'USD', netMinor: 100 },
            { currency: 'KWD', netMinor: -1234 },
          ],
        },
        {
          id: 'home',
          name: 'Home',
          simplifyDebts: true,
          balances: [
            { currency: 'USD', netMinor: -100 },
            { currency: 'KWD', netMinor: 1234 },
          ],
        },
      ],
    });
    harness.detectChanges();
    expect(text()).toContain('Pat saved');
    expect(text()).toContain('Even overall');
    expect(text()).toContain('open group obligations');
    expect(text()).toContain('KWD');
    expect(text()).toContain('1.234');
    expect(text()).toContain('Direct obligations');
    expect(text()).toContain('Simplified obligations');
    expect(root().querySelector('a[href="/groups/trip"]')).not.toBeNull();
    expect(root().querySelector('a[href="/groups/home"]')).not.toBeNull();
    expect(text()).not.toContain('Add expense');
    expect(text()).not.toContain('Settle up');
  });

  it('saves only an explicitly chosen registered canonical person from a current group', async () => {
    await harness.navigateByUrl('/friends');
    http.expectOne('/api/friends').flush([]);
    harness.detectChanges();
    button('Add friend').click();
    http
      .expectOne('/api/groups')
      .flush([{ id: 'trip', name: 'Trip', type: 'TRIP', memberCount: 2 }]);
    harness.detectChanges();
    http.expectNone('/api/groups/trip');
    select('Source group', 'trip');
    http.expectOne('/api/groups/trip').flush(sourceGroup);
    harness.detectChanges();
    const options = [
      ...root().querySelectorAll<HTMLOptionElement>('select[aria-label="Person"] option'),
    ];
    expect(options.some((o) => o.textContent?.includes('My alias'))).toBe(false);
    expect(options.filter((o) => o.value === 'pat')).toHaveLength(1);
    expect(options.some((o) => o.value === 'pat-alias')).toBe(false);
    expect(options.find((o) => o.value === 'guest')?.disabled).toBe(true);
    expect(text()).toContain('claim');
    http.expectNone((r) => r.method === 'POST');
    select('Person', 'pat');
    button('Save friend').click();
    const save = http.expectOne('/api/friends');
    expect(save.request.method).toBe('POST');
    expect(save.request.body).toEqual({ groupId: 'trip', participantId: 'pat' });
    save.flush(friend);
    http.expectOne('/api/friends').flush([friend]);
    harness.detectChanges();
    expect(text()).toContain('Pat saved');
    expect(text()).toContain('Friend saved');
  });

  it('removes only a confirmed bookmark and reviews uncertain deletion without retrying a write', async () => {
    await harness.navigateByUrl('/friends');
    http.expectOne('/api/friends').flush([friend]);
    harness.detectChanges();
    button('Remove friend').click();
    harness.detectChanges();
    expect(text()).toContain('Balances, group membership and financial history stay unchanged');
    http.expectNone((r) => r.method === 'DELETE');
    button('Keep friend').click();
    harness.detectChanges();
    button('Remove friend').click();
    harness.detectChanges();
    button('Remove saved friend').click();
    const remove = http.expectOne('/api/friends/pat');
    expect(remove.request.method).toBe('DELETE');
    remove.flush({}, { status: 503, statusText: 'Unavailable' });
    harness.detectChanges();
    expect(text()).toContain('may have changed');
    http.expectNone((r) => r.method !== 'GET');
    button('Check saved status').click();
    http.expectOne('/api/friends').flush([friend]);
    harness.detectChanges();
    expect(text()).toContain('Pat saved');
    button('Remove friend').click();
    harness.detectChanges();
    button('Remove saved friend').click();
    http.expectOne('/api/friends/pat').flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/friends').flush([]);
    harness.detectChanges();
    expect(text()).toContain('No saved friends yet');
  });

  it('requires an explicit status read after a lost save and keeps a failed review unavailable', async () => {
    await harness.navigateByUrl('/friends');
    http.expectOne('/api/friends').flush([]);
    harness.detectChanges();
    button('Add friend').click();
    http
      .expectOne('/api/groups')
      .flush([{ id: 'trip', name: 'Trip', type: 'TRIP', memberCount: 2 }]);
    harness.detectChanges();
    select('Source group', 'trip');
    http.expectOne('/api/groups/trip').flush(sourceGroup);
    harness.detectChanges();
    select('Person', 'pat');
    button('Save friend').click();
    http.expectOne('/api/friends').error(new ProgressEvent('network'));
    harness.detectChanges();
    expect(text()).toContain('may have changed');
    expect(button('Save friend').disabled).toBe(true);
    http.expectNone((r) => r.method === 'POST');
    button('Check saved status').click();
    http.expectOne('/api/friends').flush({}, { status: 503, statusText: 'Unavailable' });
    harness.detectChanges();
    expect(text()).toContain('Could not load saved friends');
    expect(text()).not.toContain('No saved friends yet');
    button('Check saved status').click();
    http.expectOne('/api/friends').flush([friend]);
    harness.detectChanges();
    expect(text()).toContain('Pat saved');
    expect(text()).not.toContain('may have changed');
    http.expectNone((r) => r.method === 'POST');
  });

  it('cancels old source reads across group A to B to A and closing the picker', async () => {
    await harness.navigateByUrl('/friends');
    http.expectOne('/api/friends').flush([]);
    harness.detectChanges();
    button('Add friend').click();
    http.expectOne('/api/groups').flush([
      { id: 'trip', name: 'Trip', type: 'TRIP', memberCount: 2 },
      { id: 'home', name: 'Home', type: 'HOME', memberCount: 2 },
    ]);
    harness.detectChanges();
    select('Source group', 'trip');
    const firstA = http.expectOne('/api/groups/trip');
    select('Source group', 'home');
    const firstB = http.expectOne('/api/groups/home');
    expect(firstA.cancelled).toBe(true);
    select('Source group', 'trip');
    expect(firstB.cancelled).toBe(true);
    http.expectOne('/api/groups/trip').flush(sourceGroup);
    harness.detectChanges();
    expect(text()).toContain('Pat');
    select('Source group', 'home');
    const abandoned = http.expectOne('/api/groups/home');
    button('Cancel adding').click();
    harness.detectChanges();
    expect(abandoned.cancelled).toBe(true);
    button('Add friend').click();
    const abandonedGroups = http.expectOne('/api/groups');
    harness.detectChanges();
    button('Cancel adding').click();
    harness.detectChanges();
    expect(abandonedGroups.cancelled).toBe(true);
    expect(root().querySelector('select')).toBeNull();
    http.expectNone((r) => r.method === 'POST');
  });

  it('cancels detail route A to B to A and displays only the latest authorized response', async () => {
    await harness.navigateByUrl('/friends/pat');
    const firstA = http.expectOne('/api/friends/pat');
    await harness.navigateByUrl('/friends/other');
    const firstB = http.expectOne('/api/friends/other');
    expect(firstA.cancelled).toBe(true);
    await harness.navigateByUrl('/friends/pat');
    expect(firstB.cancelled).toBe(true);
    http.expectOne('/api/friends/pat').flush({ ...friend, sharedGroups: [] });
    harness.detectChanges();
    expect(text()).toContain('Pat saved');
    expect(text()).not.toContain('other');
    await harness.navigateByUrl('/friends/absent');
    http.expectOne('/api/friends/absent').flush({}, { status: 404, statusText: 'Not Found' });
    harness.detectChanges();
    expect(text()).not.toContain('Pat saved');
    expect(text()).toContain('Saved friend unavailable');
    expect(text()).not.toContain('All square');
  });

  it('clears account-owned UI and cancels reads and writes through session A to B to A', async () => {
    const other = { id: 'other', displayName: 'Other', email: 'other@test.com' };
    await harness.navigateByUrl('/friends');
    const abandonedRead = http.expectOne('/api/friends');
    storeTestSession(other);
    harness.detectChanges();
    expect(abandonedRead.cancelled).toBe(true);
    expect(text()).not.toContain('No saved friends yet');
    button('Try again').click();
    const otherRead = http.expectOne('/api/friends');
    storeTestSession(me);
    harness.detectChanges();
    expect(otherRead.cancelled).toBe(true);
    button('Try again').click();
    http.expectOne('/api/friends').flush([]);
    harness.detectChanges();
    button('Add friend').click();
    http
      .expectOne('/api/groups')
      .flush([{ id: 'trip', name: 'Trip', type: 'TRIP', memberCount: 2 }]);
    harness.detectChanges();
    select('Source group', 'trip');
    http.expectOne('/api/groups/trip').flush(sourceGroup);
    harness.detectChanges();
    select('Person', 'pat');
    button('Save friend').click();
    const abandonedSave = http.expectOne('/api/friends');
    storeTestSession(other);
    harness.detectChanges();
    expect(abandonedSave.cancelled).toBe(true);
    expect(root().querySelector('select')).toBeNull();
    expect(text()).not.toContain('Friend saved');
    button('Try again').click();
    http.expectOne('/api/friends').flush([friend]);
    harness.detectChanges();
    button('Remove friend').click();
    harness.detectChanges();
    button('Remove saved friend').click();
    const abandonedDelete = http.expectOne('/api/friends/pat');
    storeTestSession(me);
    harness.detectChanges();
    expect(abandonedDelete.cancelled).toBe(true);
    expect(text()).not.toContain('Pat saved');
    expect(text()).not.toContain('Saved friend removed');
    button('Try again').click();
    http.expectOne('/api/friends').flush([]);
    harness.detectChanges();
    expect(text()).toContain('No saved friends yet');
  });

  it('coalesces foreground reads and clears unavailable or lost-access group details', async () => {
    await harness.navigateByUrl('/friends/pat');
    const initial = http.expectOne('/api/friends/pat');
    window.dispatchEvent(new Event('focus'));
    document.dispatchEvent(new Event('visibilitychange'));
    http.expectNone('/api/friends/pat');
    initial.flush({
      ...friend,
      sharedGroups: [
        {
          id: 'private',
          name: 'Private trip',
          simplifyDebts: false,
          balances: [{ currency: 'USD', netMinor: 0 }],
        },
      ],
    });
    harness.detectChanges();
    expect(text()).toContain('Private trip');
    window.dispatchEvent(new Event('focus'));
    const refresh = http.expectOne('/api/friends/pat');
    window.dispatchEvent(new Event('focus'));
    http.expectNone('/api/friends/pat');
    refresh.flush({}, { status: 503, statusText: 'Unavailable' });
    harness.detectChanges();
    expect(text()).not.toContain('Private trip');
    expect(text()).not.toContain('All square');
    window.dispatchEvent(new Event('focus'));
    http
      .expectOne('/api/friends/pat')
      .flush({ ...friend, sharedGroupCount: 0, balances: [], sharedGroups: [] });
    harness.detectChanges();
    expect(text()).toContain('Pat saved');
    expect(text()).toContain('No current shared group data');
    expect(root().querySelector('a[href="/groups/private"]')).toBeNull();
    window.dispatchEvent(new Event('focus'));
    const abandoned = http.expectOne('/api/friends/pat');
    await harness.navigateByUrl('/friends');
    expect(abandoned.cancelled).toBe(true);
    http.expectOne('/api/friends').flush([]);
    harness.detectChanges();
    http.expectNone((r) => r.method !== 'GET');
  });

  it('returns to the directory after confirmed detail removal without fetching a deleted detail', async () => {
    await harness.navigateByUrl('/friends/pat');
    http.expectOne('/api/friends/pat').flush({ ...friend, sharedGroups: [] });
    harness.detectChanges();
    button('Remove friend').click();
    harness.detectChanges();
    button('Remove saved friend').click();
    http.expectOne('/api/friends/pat').flush(null, { status: 204, statusText: 'No Content' });
    await new Promise((resolve) => setTimeout(resolve, 0));
    harness.detectChanges();
    expect(TestBed.inject(Router).url).toBe('/friends');
    http.expectNone('/api/friends/pat');
    http.expectOne('/api/friends').flush([]);
    harness.detectChanges();
    expect(text()).toContain('No saved friends yet');
    expect(text()).not.toContain('unavailable');
  });

  it('checks the owned directory after a dropped detail deletion before confirming absence', async () => {
    await harness.navigateByUrl('/friends/pat');
    http.expectOne('/api/friends/pat').flush({ ...friend, sharedGroups: [] });
    harness.detectChanges();
    button('Remove friend').click();
    harness.detectChanges();
    button('Remove saved friend').click();
    http.expectOne('/api/friends/pat').error(new ProgressEvent('network'));
    harness.detectChanges();
    button('Check saved status').click();
    http.expectOne('/api/friends').flush({}, { status: 503, statusText: 'Unavailable' });
    harness.detectChanges();
    expect(text()).toContain('may have changed');
    expect(TestBed.inject(Router).url).toBe('/friends/pat');
    button('Check saved status').click();
    http.expectOne('/api/friends').flush([]);
    await new Promise((resolve) => setTimeout(resolve, 0));
    harness.detectChanges();
    expect(TestBed.inject(Router).url).toBe('/friends');
    http.expectOne('/api/friends').flush([]);
    harness.detectChanges();
    expect(text()).toContain('No saved friends yet');
    http.expectNone('/api/friends/pat');
    http.expectNone((r) => r.method !== 'GET');
  });

  it('clears loading when the source group is deselected and lets the person choose again', async () => {
    await harness.navigateByUrl('/friends');
    http.expectOne('/api/friends').flush([]);
    harness.detectChanges();
    button('Add friend').click();
    http
      .expectOne('/api/groups')
      .flush([{ id: 'trip', name: 'Trip', type: 'TRIP', memberCount: 2 }]);
    harness.detectChanges();
    select('Source group', 'trip');
    const abandoned = http.expectOne('/api/groups/trip');
    select('Source group', '');
    expect(abandoned.cancelled).toBe(true);
    expect(text()).not.toContain('Loading source people');
    expect(root().querySelector('select[aria-label="Person"]')).toBeNull();
    select('Source group', 'trip');
    http.expectOne('/api/groups/trip').flush(sourceGroup);
    harness.detectChanges();
    select('Person', 'pat');
    expect(button('Save friend').disabled).toBe(false);
    http.expectNone((r) => r.method === 'POST');
  });
});
