import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthService } from '../core/auth/auth.service';
import { DashboardResponse } from '../dashboard/dashboard.models';
import { DashboardService } from '../dashboard/dashboard.service';
import { ExpenseResponse } from '../expenses/expense.models';
import { GroupResponse } from '../groups/group.models';
import { ModalService } from './modal.service';
import { NewExpenseModalComponent } from './new-expense-modal.component';

const me = { id: 'me', email: 'me@example.test', displayName: 'Alex Rivera' };
const other = { id: 'other', email: 'other@example.test', displayName: 'Noor Kapoor' };
const group: GroupResponse = {
  id: 'current-group',
  name: 'Current group',
  type: 'HOME',
  createdBy: me,
  members: [me, other],
  simplifyDebts: true,
  createdAt: '2026-10-17T00:00:00Z',
};
const expense: ExpenseResponse = {
  id: 'current-expense',
  groupId: group.id,
  description: 'Original lunch',
  amountCents: 1000,
  splitType: 'EQUAL',
  category: 'FOOD_DRINK',
  spentOn: '2026-10-17',
  paidBy: me,
  participants: [
    { user: me, shareCents: 500 },
    { user: other, shareCents: 500 },
  ],
  createdAt: '2026-10-17T00:00:00Z',
};
const emptyOverview: DashboardResponse = {
  totalNetCents: 0,
  owedCents: 0,
  owedPeopleCount: 0,
  oweCents: 0,
  owePeopleCount: 0,
  groupCount: 0,
  groups: [],
  people: [],
  settlements: [],
  activity: [],
};

describe('expense form independent of overview availability', () => {
  let http: HttpTestingController;
  let dashboard: DashboardService;
  let modal: ModalService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [NewExpenseModalComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { user: signal(me) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    dashboard = TestBed.inject(DashboardService);
    modal = TestBed.inject(ModalService);
  });
  afterEach(() => http.verify());

  for (const mode of ['create', 'edit'] as const) {
    it.each(['pending', 'failed'] as const)(
      `can ${mode} in the scoped group while the overview is %s`,
      (availability) => {
        dashboard.ensureLoaded();
        const initialOverview = http.expectOne('/api/dashboard');
        if (availability === 'failed') {
          initialOverview.flush({}, { status: 503, statusText: 'Unavailable' });
        }
        modal.openExpense(group.id, mode === 'edit' ? expense.id : undefined);
        const fixture = TestBed.createComponent(NewExpenseModalComponent);
        http.expectOne(`/api/groups/${group.id}`).flush(group);
        if (mode === 'edit') {
          http.expectOne(`/api/groups/${group.id}/expenses/${expense.id}`).flush(expense);
        }
        fixture.detectChanges();
        const root: HTMLElement = fixture.nativeElement;
        const description = root.querySelector<HTMLInputElement>('#ne-desc');
        const amount = root.querySelector<HTMLInputElement>('#ne-amt');
        expect(description).not.toBeNull();
        expect(amount).not.toBeNull();
        expect(root.textContent).not.toContain('Create a group before adding an expense.');
        if (mode === 'edit') expect(description!.value).toBe(expense.description);
        description!.value = 'Updated lunch';
        description!.dispatchEvent(new Event('input', { bubbles: true }));
        amount!.value = '12.34';
        amount!.dispatchEvent(new Event('input', { bubbles: true }));
        fixture.detectChanges();
        expect(root.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(false);
        root
          .querySelector('form')!
          .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
        const save = http.expectOne({
          method: mode === 'edit' ? 'PUT' : 'POST',
          url: `/api/groups/${group.id}/expenses${mode === 'edit' ? `/${expense.id}` : ''}`,
        });
        expect(save.request.body).toMatchObject({
          description: 'Updated lunch',
          amountCents: 1234,
          paidByUserId: me.id,
          participantUserIds: [me.id, other.id],
        });
        save.flush({ ...expense, description: 'Updated lunch', amountCents: 1234 });
        if (availability === 'pending') initialOverview.flush(emptyOverview);
        http.expectOne('/api/dashboard').flush(emptyOverview);
        expect(modal.active()).toBeNull();
      },
    );
  }

  it('still directs an unscoped account with no groups to create its first group', () => {
    dashboard.ensureLoaded();
    http.expectOne('/api/dashboard').flush(emptyOverview);
    modal.openExpense();
    const fixture = TestBed.createComponent(NewExpenseModalComponent);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('#ne-desc')).toBeNull();
    expect(root.textContent).toContain('Create a group before adding an expense.');
    root.querySelector<HTMLButtonElement>('.mform .dc-btn-outline')!.click();
    expect(modal.active()).toBe('group');
  });
});
