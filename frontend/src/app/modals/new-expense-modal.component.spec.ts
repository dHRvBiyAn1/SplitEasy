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
const third = { id: 'third', email: 'third@example.test', displayName: 'Bob Chen' };
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

describe('expense modal group ownership and overview availability', () => {
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
  afterEach(() => {
    try {
      http.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  const secondGroup: GroupResponse = {
    ...group,
    id: 'second-group',
    name: 'Second group',
    members: [me, other, third],
  };

  function openForm(edit = false) {
    dashboard.ensureLoaded();
    http.expectOne('/api/dashboard').flush({
      ...emptyOverview,
      groups: [group, secondGroup].map((g) => ({
        ...g,
        memberCount: g.members.length,
        totalSpentCents: 0,
        youAreOwedCents: 0,
        youOweCents: 0,
        netCents: 0,
      })),
    });
    modal.openExpense(group.id, edit ? expense.id : undefined);
    const fixture = TestBed.createComponent(NewExpenseModalComponent);
    fixture.detectChanges();
    return fixture;
  }

  function button(root: HTMLElement, text: string): HTMLButtonElement {
    return [...root.querySelectorAll<HTMLButtonElement>('button')].find((b) =>
      b.textContent?.trim().endsWith(text),
    )!;
  }

  function fillAmount(root: HTMLElement) {
    const description = root.querySelector<HTMLInputElement>('#ne-desc')!;
    description.value = 'Lunch';
    description.dispatchEvent(new Event('input', { bubbles: true }));
    const amount = root.querySelector<HTMLInputElement>('#ne-amt')!;
    amount.value = '12';
    amount.dispatchEvent(new Event('input', { bubbles: true }));
  }

  function submit(root: HTMLElement) {
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
  }

  it('keeps B members, payer and participants when B resolves before stale A', () => {
    const fixture = openForm();
    const root: HTMLElement = fixture.nativeElement;
    const pendingA = http.expectOne(`/api/groups/${group.id}`);
    button(root, secondGroup.name).click();
    http.expectOne(`/api/groups/${secondGroup.id}`).flush(secondGroup);
    fixture.detectChanges();
    button(root, 'Bob').click();
    pendingA.flush(group);
    fixture.detectChanges();
    expect(root.querySelectorAll('.prow')).toHaveLength(3);
    expect(button(root, 'Bob').getAttribute('aria-pressed')).toBe('true');
    fillAmount(root);
    fixture.detectChanges();
    submit(root);
    const save = http.expectOne({ method: 'POST', url: `/api/groups/${secondGroup.id}/expenses` });
    expect(save.request.body).toMatchObject({
      paidByUserId: third.id,
      participantUserIds: [me.id, other.id, third.id],
    });
    save.flush(expense);
    http.expectOne('/api/dashboard').flush(emptyOverview);
  });

  it.each(['EQUAL', 'UNEQUAL', 'PERCENTAGE'] as const)(
    'clears previous members, payer and %s inputs while the next group is pending',
    (split) => {
      const fixture = openForm();
      http.expectOne(`/api/groups/${group.id}`).flush(group);
      fixture.detectChanges();
      const root: HTMLElement = fixture.nativeElement;
      fillAmount(root);
      if (split !== 'EQUAL') {
        button(root, split === 'UNEQUAL' ? 'Unequal' : 'Percent').click();
        fixture.detectChanges();
        const input = root.querySelector<HTMLInputElement>('.prow__val')!;
        input.value = split === 'UNEQUAL' ? '12' : '100';
        input.dispatchEvent(new Event('input', { bubbles: true }));
      }
      fixture.detectChanges();
      expect(button(root, 'Save expense').disabled).toBe(false);
      button(root, secondGroup.name).click();
      const nextGroup = http.expectOne(`/api/groups/${secondGroup.id}`);
      fixture.detectChanges();
      expect(root.querySelectorAll('.prow')).toHaveLength(0);
      expect(root.textContent).not.toContain('Noor');
      expect(button(root, 'Save expense').disabled).toBe(true);
      submit(root);
      http.expectNone((r) => r.method === 'POST');
      nextGroup.flush(secondGroup);
      fixture.detectChanges();
      expect(root.querySelectorAll('.prow')).toHaveLength(3);
      if (split !== 'EQUAL') {
        expect(
          [...root.querySelectorAll<HTMLInputElement>('.prow__val')].every((i) => !i.value),
        ).toBe(true);
        expect(button(root, 'Save expense').disabled).toBe(true);
      }
    },
  );

  it('reports a member-load failure without retaining selectable old-group members', () => {
    const fixture = openForm();
    http.expectOne(`/api/groups/${group.id}`).flush(group);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    fillAmount(root);
    button(root, secondGroup.name).click();
    http
      .expectOne(`/api/groups/${secondGroup.id}`)
      .flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(root.querySelector('[role=alert]')?.textContent).toContain(
      'Could not load group members.',
    );
    expect(root.querySelectorAll('.prow')).toHaveLength(0);
    expect(root.textContent).not.toContain('Noor');
    expect(button(root, 'Save expense').disabled).toBe(true);
    submit(root);
    http.expectNone((r) => r.method === 'POST');
  });

  it('ignores an older same-group response after A to B to A', () => {
    const fixture = openForm();
    const root: HTMLElement = fixture.nativeElement;
    const oldA = http.expectOne(`/api/groups/${group.id}`);
    button(root, secondGroup.name).click();
    const pendingB = http.expectOne(`/api/groups/${secondGroup.id}`);
    button(root, group.name).click();
    http.expectOne(`/api/groups/${group.id}`).flush({ ...group, members: [me] });
    pendingB.flush(secondGroup);
    oldA.flush(group);
    fixture.detectChanges();
    expect(root.querySelectorAll('.prow')).toHaveLength(1);
    expect(root.textContent).not.toContain('Noor');
  });

  it('ignores stale member failures after the selected group has loaded', () => {
    const fixture = openForm();
    const root: HTMLElement = fixture.nativeElement;
    const oldA = http.expectOne(`/api/groups/${group.id}`);
    button(root, secondGroup.name).click();
    http.expectOne(`/api/groups/${secondGroup.id}`).flush(secondGroup);
    oldA.flush({}, { status: 503, statusText: 'Unavailable' });
    fillAmount(root);
    fixture.detectChanges();
    expect(root.querySelector('[role=alert]')).toBeNull();
    expect(root.querySelectorAll('.prow')).toHaveLength(3);
    expect(button(root, 'Save expense').disabled).toBe(false);
  });

  it('locks an edited expense to its original group and submits the original PUT path', () => {
    const fixture = openForm(true);
    http.expectOne(`/api/groups/${group.id}`).flush(group);
    http.expectOne(`/api/groups/${group.id}/expenses/${expense.id}`).flush(expense);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    const anotherGroup = button(root, secondGroup.name);
    expect(anotherGroup.disabled).toBe(true);
    anotherGroup.click();
    // The component guard also protects non-button callers.
    fixture.componentInstance.selectGroup(secondGroup.id);
    http.expectNone(`/api/groups/${secondGroup.id}`);
    fillAmount(root);
    submit(root);
    const save = http.expectOne({
      method: 'PUT',
      url: `/api/groups/${group.id}/expenses/${expense.id}`,
    });
    expect(save.request.body.participantUserIds).toEqual([me.id, other.id]);
    save.flush(expense);
    http.expectOne('/api/dashboard').flush(emptyOverview);
  });

  it('waits for the existing expense before enabling save', () => {
    const fixture = openForm(true);
    http.expectOne(`/api/groups/${group.id}`).flush(group);
    const pendingExpense = http.expectOne(`/api/groups/${group.id}/expenses/${expense.id}`);
    const root: HTMLElement = fixture.nativeElement;
    fillAmount(root);
    fixture.detectChanges();
    button(root, 'You').click();
    root.querySelector<HTMLInputElement>('.prow__check')!.click();
    fixture.detectChanges();
    expect(button(root, 'Save expense').disabled).toBe(true);
    submit(root);
    http.expectNone((r) => r.method === 'PUT');
    pendingExpense.flush(expense);
    fixture.detectChanges();
    expect(button(root, 'Save expense').disabled).toBe(false);
  });

  it('reports a failed expense prefill and keeps saving disabled', () => {
    const fixture = openForm(true);
    http.expectOne(`/api/groups/${group.id}`).flush(group);
    http
      .expectOne(`/api/groups/${group.id}/expenses/${expense.id}`)
      .flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    fillAmount(root);
    button(root, 'You').click();
    root.querySelector<HTMLInputElement>('.prow__check')!.click();
    fixture.detectChanges();
    expect(root.querySelector('[role=alert]')?.textContent).toContain(
      'Could not load the expense.',
    );
    expect(button(root, 'Save expense').disabled).toBe(true);
    submit(root);
    http.expectNone((r) => r.method === 'PUT');
  });

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

  it('shows a conflicting edit message and keeps the form open without retrying', () => {
    modal.openExpense(group.id, expense.id);
    const fixture = TestBed.createComponent(NewExpenseModalComponent);
    http.expectOne(`/api/groups/${group.id}`).flush(group);
    http.expectOne(`/api/groups/${group.id}/expenses/${expense.id}`).flush(expense);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    const description = root.querySelector<HTMLInputElement>('#ne-desc')!;
    description.value = 'My edit';
    description.dispatchEvent(new Event('input', { bubbles: true }));
    root
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    const message =
      'This record was changed by another request. Reload and review the latest changes before saving again.';
    http
      .expectOne({ method: 'PUT', url: `/api/groups/${group.id}/expenses/${expense.id}` })
      .flush({ status: 409, error: 'Conflict', message }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(root.textContent).toContain(message);
    expect(description.value).toBe('My edit');
    expect(root.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(false);
    expect(modal.active()).toBe('expense');
    http.expectNone(`/api/groups/${group.id}/expenses/${expense.id}`);
    http.expectNone('/api/dashboard');
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
