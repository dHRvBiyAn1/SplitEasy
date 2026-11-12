import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { storeTestSession } from '../core/auth/auth-test-fixture';
import { ModalService } from './modal.service';
import { SettleUpModalComponent } from './settle-up-modal.component';

const me = { id: 'me', displayName: 'Alex', email: 'alex@test.com' };
const pat = { id: 'pat', displayName: 'Pat', email: null };

describe('settle-up modal scopes', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    storeTestSession(me);
  });
  afterEach(() => {
    http.match('/api/dashboard');
    http.verify({ ignoreCancelled: true });
  });

  async function render(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(SettleUpModalComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  it('records a friend settlement at /friends/{id}/payments and never touches /groups', async () => {
    TestBed.inject(ModalService).openSettle({
      friendId: 'pat',
      groupName: 'Personal',
      counterparty: pat,
      netMinor: -500,
      currency: 'USD',
    });
    const el = await render();
    el.querySelector<HTMLButtonElement>('.dc-btn-accent')!.click();
    const post = http.expectOne((r) => r.method === 'POST' && r.url.includes('/payments'));
    expect(post.request.url).toBe('/api/friends/pat/payments');
    expect(post.request.body).toMatchObject({
      payerUserId: 'me',
      payeeUserId: 'pat',
      amountMinor: 500,
      currency: 'USD',
    });
    expect(http.match((r) => r.url.includes('/groups'))).toHaveLength(0);
    post.flush({});
  });

  it('keeps a group settlement on the group route', async () => {
    TestBed.inject(ModalService).openSettle({
      groupId: 'g1',
      groupName: 'Trip',
      counterparty: pat,
      netMinor: 500,
      currency: 'USD',
    });
    const el = await render();
    el.querySelector<HTMLButtonElement>('.dc-btn-accent')!.click();
    const post = http.expectOne((r) => r.method === 'POST' && r.url.includes('/payments'));
    expect(post.request.url).toBe('/api/groups/g1/payments');
    expect(http.match((r) => r.url.includes('/friends'))).toHaveLength(0);
    post.flush({});
  });
});
