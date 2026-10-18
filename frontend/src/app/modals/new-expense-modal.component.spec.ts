import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthService } from '../core/auth/auth.service';
import { DashboardResponse } from '../dashboard/dashboard.models';
import { DashboardService } from '../dashboard/dashboard.service';
import { CreateExpenseRequest, ExpenseResponse } from '../expenses/expense.models';
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

describe('serialized expense split validation', () => {
  let http: HttpTestingController;
  const third = { id: 'third', email: 'third@example.test', displayName: 'Sam Lee' };
  const members = [me, other, third];

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
  });
  afterEach(() => http.verify());

  function open(type: 'UNEQUAL' | 'PERCENTAGE', values: string[], edit = false) {
    TestBed.inject(ModalService).openExpense(group.id, edit ? expense.id : undefined);
    const fixture = TestBed.createComponent(NewExpenseModalComponent);
    http.expectOne(`/api/groups/${group.id}`).flush({ ...group, members });
    if (edit) {
      http.expectOne(`/api/groups/${group.id}/expenses/${expense.id}`).flush({
        ...expense,
        splitType: 'PERCENTAGE',
        participants: [
          { user: me, shareCents: 333 },
          { user: other, shareCents: 667 },
        ],
      });
    }
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    if (!edit) {
      for (const [selector, value] of [
        ['#ne-desc', 'Lunch'],
        ['#ne-amt', '100'],
      ]) {
        const input = root.querySelector<HTMLInputElement>(selector)!;
        input.value = value;
        input.dispatchEvent(new Event('input', { bubbles: true }));
      }
      root.querySelectorAll<HTMLButtonElement>('.seg button')[type === 'UNEQUAL' ? 1 : 2].click();
      values.forEach((value, i) => fixture.componentInstance.setValue(members[i].id, value));
      fixture.detectChanges();
    }
    return { fixture, root };
  }

  function submit(fixture: ReturnType<typeof open>['fixture'], method = 'POST') {
    fixture.componentInstance.save();
    const save = http.expectOne({
      method,
      url: `/api/groups/${group.id}/expenses${method === 'PUT' ? `/${expense.id}` : ''}`,
    });
    const body: CreateExpenseRequest = save.request.body;
    expect(body.splits!.every((s) => Number.isSafeInteger(s.value) && s.value >= 0)).toBe(true);
    expect(body.splits!.reduce((total, s) => total + s.value, 0)).toBe(
      body.splitType === 'PERCENTAGE' ? 10000 : body.amountCents,
    );
    save.flush(expense);
    http.expectOne('/api/dashboard').flush(emptyOverview);
    return body;
  }

  it('shows the serialized rounded percentage total and blocks the reported thirds', () => {
    const { fixture, root } = open('PERCENTAGE', ['33.335', '33.335', '33.33']);
    expect(fixture.componentInstance.runningLabel()).toBe('100.01% / 100%');
    expect(fixture.componentInstance.splitValid()).toBe(false);
    expect(root.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(true);
    fixture.componentInstance.save();
    http.expectNone((request) => request.method === 'POST');
  });

  for (const type of ['UNEQUAL', 'PERCENTAGE'] as const) {
    it.each(['-5', '-0.001', 'NaN', 'Infinity', '-Infinity', '1e309', '9007199254740991'])(
      `rejects invalid ${type} shares (%s) before filtering or rounding`,
      (invalid) => {
        const { fixture, root } = open(type, [invalid, invalid === '-5' ? '105' : '100', '0']);
        expect(fixture.componentInstance.splitValid()).toBe(false);
        expect(fixture.componentInstance.runningLabel()).toBe('Enter valid nonnegative shares');
        expect(root.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(true);
        fixture.componentInstance.save();
        http.expectNone((request) => request.method === 'POST');
      },
    );

    it(`sends valid two-decimal ${type} shares using the displayed total`, () => {
      const { fixture, root } = open(type, ['33.33', '33.33', '33.34']);
      expect(fixture.componentInstance.splitValid()).toBe(true);
      expect(root.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(false);
      expect(submit(fixture).splits!.map((s) => s.value)).toEqual([3333, 3333, 3334]);
    });

    it(`omits zero ${type} shares, including values rounded to zero`, () => {
      const { fixture } = open(type, ['100', '0', '0.001']);
      expect(fixture.componentInstance.splitValid()).toBe(true);
      expect(submit(fixture).splits).toEqual([{ userId: me.id, value: 10000 }]);
    });

    it(`rounds each ${type} share to the nearest hundredth before validating and sending`, () => {
      const { fixture } = open(type, ['33.334', '33.334', '33.335']);
      expect(fixture.componentInstance.splitValid()).toBe(true);
      expect(submit(fixture).splits!.map((s) => s.value)).toEqual([3333, 3333, 3334]);
    });
  }

  it('validates exact cents rather than the unrounded dollar sum', () => {
    const { fixture } = open('UNEQUAL', ['33.335', '33.335', '33.33']);
    expect(fixture.componentInstance.runningLabel()).toBe('$100.01 / $100.00');
    expect(fixture.componentInstance.splitValid()).toBe(false);
    fixture.componentInstance.save();
    http.expectNone((request) => request.method === 'POST');
  });

  it('preserves lossless exact edit prefill for an existing percentage expense', () => {
    const { fixture, root } = open('UNEQUAL', [], true);
    expect(
      root.querySelectorAll<HTMLButtonElement>('.seg button')[1].getAttribute('aria-pressed'),
    ).toBe('true');
    expect(fixture.componentInstance.runningLabel()).toBe('$10.00 / $10.00');
    expect(submit(fixture, 'PUT').splits).toEqual([
      { userId: me.id, value: 333 },
      { userId: other.id, value: 667 },
    ]);
  });
});
