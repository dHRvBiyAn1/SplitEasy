import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { storeTestSession } from '../core/auth/auth-test-fixture';
import { DashboardService } from '../dashboard/dashboard.service';
import { ModalService } from './modal.service';
import { SettleUpModalComponent } from './settle-up-modal.component';
const me = { id: 'account', displayName: 'Alex', email: 'alex@test.com' };
const guest = {
  id: 'guest',
  displayName: 'Pat',
  email: null,
  canonicalParticipantId: 'guest',
  accountId: null,
};
describe('participant settlement and immutable original retries', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      imports: [SettleUpModalComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    storeTestSession(me);
  });
  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
    sessionStorage.clear();
  });
  it('records guest settlement with server viewer identity and retries saved original IDs after projection changes', () => {
    const dashboard = TestBed.inject(DashboardService);
    dashboard.refresh();
    http.expectOne('/api/dashboard').flush({
      viewerParticipantId: 'primary',
      ownedParticipantIds: ['primary', 'alias'],
      groups: [],
      settlements: [{ groupId: 'g', groupName: 'Trip', counterparty: guest, netMinor: -500 }],
      people: [],
      activity: [],
    });
    TestBed.inject(ModalService).openSettle(undefined, 'g');
    const fixture = TestBed.createComponent(SettleUpModalComponent);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.textContent).not.toContain('(null)');
    root.querySelector<HTMLButtonElement>('.srow')!.click();
    fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('.dc-btn-accent')!.click();
    const save = http.expectOne('/api/groups/g/payments');
    expect(save.request.body.payerUserId).toBe('primary');
    const original = save.request.body;
    save.flush({}, { status: 503, statusText: 'Unavailable' });
    dashboard.refresh();
    http.expectOne('/api/dashboard').flush({
      viewerParticipantId: 'changed-primary',
      ownedParticipantIds: ['changed-primary'],
      groups: [],
      settlements: [],
      people: [],
      activity: [],
    });
    fixture.detectChanges();
    [...root.querySelectorAll<HTMLButtonElement>('button')]
      .find((b) => b.textContent?.includes('Retry original'))!
      .click();
    const retry = http.expectOne('/api/groups/g/payments');
    expect(retry.request.body).toEqual(original);
    retry.flush({}, { status: 409, statusText: 'Conflict' });
  });
  it('blocks an unsupported saved identity schema with upgrade guidance before any retry dispatch', () => {
    sessionStorage.setItem(
      'spliteasy.pending-write.v1.account.payment',
      JSON.stringify({
        owner: 'account',
        scope: 'g',
        operationId: 'saved-operation',
        identityVersion: 99,
        request: {
          payerUserId: 'old-payer',
          payeeUserId: 'old-payee',
          amountMinor: 500,
          currency: 'USD',
        },
        draft: { groupId: 'g', groupName: 'Trip', counterparty: guest, netMinor: -500 },
      }),
    );
    TestBed.inject(ModalService).openSettle(undefined, 'g');
    const fixture = TestBed.createComponent(SettleUpModalComponent);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.textContent).toContain('Upgrade');
    http.expectNone('/api/groups/g/payments');
  });
});
