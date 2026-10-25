import { storeTestSession } from '../core/auth/auth-test-fixture';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { DashboardService } from '../dashboard/dashboard.service';
import { ModalService } from './modal.service';
import { SettleUpModalComponent } from './settle-up-modal.component';

const me = { id: 'a', displayName: 'Alice', email: 'a@test.com' };
const carol = { id: 'c', displayName: 'Carol', email: 'c@test.com' };

describe('canonical settlement actions', () => {
  afterEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('renders and records the same simplified obligation as the dashboard without a second preview', () => {
    TestBed.configureTestingModule({
      imports: [SettleUpModalComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    const http = TestBed.inject(HttpTestingController);
    storeTestSession(me);
    TestBed.inject(DashboardService).refresh();
    http.expectOne('/api/dashboard').flush({
      groups: [{ id: 'g', name: 'Chain', simplifyDebts: true }],
      settlements: [{ groupId: 'g', groupName: 'Chain', counterparty: carol, netCents: -1000 }],
      people: [{ user: carol, netCents: -1000 }],
      activity: [],
    });
    TestBed.inject(ModalService).openSettle(undefined, 'g');
    const fixture = TestBed.createComponent(SettleUpModalComponent);
    fixture.detectChanges();
    http.expectNone('/api/groups/g/debt-simplification');
    const root: HTMLElement = fixture.nativeElement;
    expect(root.textContent).toContain('You owe Carol (c@test.com)');
    root.querySelector<HTMLButtonElement>('.srow')!.click();
    fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('.dc-btn-accent')!.click();
    const payment = http.expectOne('/api/groups/g/payments');
    expect(payment.request.body).toMatchObject({
      payerUserId: 'a',
      payeeUserId: 'c',
      amountCents: 1000,
    });
    payment.flush({ message: 'Reload' }, { status: 409, statusText: 'Conflict' });
    http.verify();
  });
  it('shows offsetting direct debts and records the displayed incoming direction', () => {
    TestBed.configureTestingModule({
      imports: [SettleUpModalComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    const http = TestBed.inject(HttpTestingController);
    storeTestSession(me);
    TestBed.inject(DashboardService).refresh();
    http.expectOne('/api/dashboard').flush({
      groups: [{ id: 'g', name: 'Cycle', simplifyDebts: false }],
      settlements: [
        {
          groupId: 'g',
          groupName: 'Cycle',
          counterparty: { id: 'b', displayName: 'Bob', email: 'b@test.com' },
          netCents: -1000,
        },
        { groupId: 'g', groupName: 'Cycle', counterparty: carol, netCents: 1000 },
      ],
      people: [],
      activity: [],
    });
    TestBed.inject(ModalService).openSettle(undefined, 'g');
    const fixture = TestBed.createComponent(SettleUpModalComponent);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.textContent).toContain('You owe Bob (b@test.com)');
    expect(root.textContent).toContain('Carol (c@test.com) owes you');
    expect(root.textContent).toContain('may leave offsetting direct obligations');
    expect(root.textContent).not.toContain('all settled');
    root.querySelectorAll<HTMLButtonElement>('.srow')[1].click();
    fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('.dc-btn-accent')!.click();
    const payment = http.expectOne('/api/groups/g/payments');
    expect(payment.request.body).toMatchObject({
      payerUserId: 'c',
      payeeUserId: 'a',
      amountCents: 1000,
    });
    payment.flush({ message: 'Reload' }, { status: 409, statusText: 'Conflict' });
    http.verify();
  });
});
