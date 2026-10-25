import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { SessionService } from '../core/auth/session.service';
import { AuthService } from '../core/auth/auth.service';
import { DashboardResponse } from '../dashboard/dashboard.models';
import { DashboardService } from '../dashboard/dashboard.service';
import { CreateExpenseRequest, ExpenseResponse } from '../expenses/expense.models';
import { GroupResponse } from '../groups/group.models';
import { ModalService } from './modal.service';
import { NewExpenseModalComponent } from './new-expense-modal.component';

// Expense-only fixtures keep the default USD draft while profile loading is unavailable.
function settleProfileRead(http: HttpTestingController): void {
  for (const request of http.match('/api/me'))
    if (!request.cancelled) request.flush({}, { status: 503, statusText: 'Unavailable' });
}

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
beforeEach(() => sessionStorage.clear());

const expense: ExpenseResponse = {
  version: 0,
  id: 'current-expense',
  groupId: group.id,
  description: 'Original lunch',
  amountMinor: 1000,
  currency: 'USD' as const,
  splitType: 'EQUAL',
  category: 'FOOD_DRINK',
  spentOn: '2026-10-17',
  paidBy: me,
  participants: [
    { user: me, shareMinor: 500 },
    { user: other, shareMinor: 500 },
  ],
  createdAt: '2026-10-17T00:00:00Z',
};
const emptyOverview: DashboardResponse = {
  totalNetMinor: 0,
  owedMinor: 0,
  owedPeopleCount: 0,
  oweMinor: 0,
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
      settleProfileRead(http);
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
        totalSpentMinor: 0,
        youAreOwedMinor: 0,
        youOweMinor: 0,
        netMinor: 0,
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

  it('retries failed current-group membership deliberately without losing typed money or description', () => {
    const fixture = openForm();
    const root: HTMLElement = fixture.nativeElement;
    http.expectOne(`/api/groups/${group.id}`).flush({}, { status: 503, statusText: 'Unavailable' });
    fillAmount(root);
    fixture.detectChanges();
    button(root, 'Retry members and expense').click();
    http.expectOne(`/api/groups/${group.id}`).flush(group);
    fixture.detectChanges();
    expect(root.querySelector<HTMLInputElement>('#ne-desc')!.value).toBe('Lunch');
    expect(root.querySelector<HTMLInputElement>('#ne-amt')!.value).toBe('12');
    expect(button(root, 'Save expense').disabled).toBe(false);
    http.expectNone((r) => r.method === 'POST');
  });

  it('keeps a dispatched expense open through Escape, backdrop and both close controls until failure', () => {
    const fixture = openForm();
    http.expectOne(`/api/groups/${group.id}`).flush(group);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    fillAmount(root);
    submit(root);
    fixture.detectChanges();
    const save = http.expectOne({ method: 'POST', url: `/api/groups/${group.id}/expenses` });
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    root.querySelector<HTMLElement>('.mdl-backdrop')!.click();
    root.querySelector<HTMLButtonElement>('.mdl-close')!.click();
    button(root, 'Cancel').click();
    expect(modal.active()).toBe('expense');
    expect(root.textContent).toContain('Saving. Please wait.');
    save.flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(root.querySelector<HTMLInputElement>('#ne-desc')!.value).toBe('Lunch');
    root.querySelector<HTMLButtonElement>('.mdl-close')!.click();
    expect(modal.active()).toBeNull();
  });

  it('politely announces the running split total after edits', () => {
    const fixture = openForm();
    http.expectOne(`/api/groups/${group.id}`).flush(group);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    fillAmount(root);
    fixture.detectChanges();
    expect(root.querySelector('.running')?.getAttribute('aria-live')).toBe('polite');
    expect(root.querySelector('.running')?.textContent).toContain('Split equally among 2');
  });

  it('keeps an edit draft when failed prefill is retried', () => {
    const fixture = openForm(true);
    http.expectOne(`/api/groups/${group.id}`).flush(group);
    http
      .expectOne(`/api/groups/${group.id}/expenses/${expense.id}`)
      .flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    fillAmount(root);
    button(root, 'Retry members and expense').click();
    http.expectOne(`/api/groups/${group.id}`).flush(group);
    http
      .expectOne(`/api/groups/${group.id}/expenses/${expense.id}`)
      .flush({ ...expense, description: 'Old server description', amountMinor: 9900 });
    fixture.detectChanges();
    expect(root.querySelector<HTMLInputElement>('#ne-desc')!.value).toBe('Lunch');
    expect(root.querySelector<HTMLInputElement>('#ne-amt')!.value).toBe('12');
  });

  it('cannot submit or retry an old account draft before its closed view is destroyed', () => {
    const fixture = openForm();
    http.expectOne(`/api/groups/${group.id}`).flush(group);
    fixture.detectChanges();
    fillAmount(fixture.nativeElement);
    TestBed.inject(SessionService).reset();
    fixture.componentInstance.save();
    http.expectNone((r) => r.method === 'POST');
  });

  it('distinguishes required member loading from an empty roster', () => {
    const fixture = openForm();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=status]')?.textContent).toContain(
      'Loading group members and expense',
    );
    http.expectOne(`/api/groups/${group.id}`).flush(group);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=status]')).toBeNull();
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
          amountMinor: 1234,
          currency: 'USD' as const,
          paidByUserId: me.id,
          participantUserIds: [me.id, other.id],
        });
        save.flush({ ...expense, description: 'Updated lunch', amountMinor: 1234 });
        if (availability === 'pending') initialOverview.flush(emptyOverview);
        http.expectOne('/api/dashboard').flush(emptyOverview);
        expect(modal.active()).toBeNull();
      },
    );
  }

  it.each(['UNEQUAL', 'PERCENTAGE'] as const)(
    'identifies duplicate-name members and retains selected state and %s validation',
    (splitType) => {
      const members = [
        me,
        { id: 'alex-two', displayName: 'Alex Chen', email: 'alex.chen@example.test' },
        { id: 'alex-three', displayName: 'Alex Chen', email: 'another.alex@example.test' },
      ];
      modal.openExpense(group.id);
      const fixture = TestBed.createComponent(NewExpenseModalComponent);
      http.expectOne(`/api/groups/${group.id}`).flush({ ...group, members });
      fixture.detectChanges();
      const root: HTMLElement = fixture.nativeElement;
      const button = (text: string) =>
        Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find((b) =>
          b.textContent?.trim().endsWith(text),
        )!;
      const payerName = (m: (typeof members)[number]) =>
        `${m.id === me.id ? 'You: ' : ''}${m.displayName} (${m.email})`;
      const payerButtons = members.map((m) =>
        root.querySelector<HTMLButtonElement>(`button[aria-label="${payerName(m)}"]`),
      );
      expect(payerButtons.every(Boolean)).toBe(true);
      expect(payerButtons.map((b) => b?.getAttribute('aria-pressed'))).toEqual([
        'true',
        'false',
        'false',
      ]);
      payerButtons[2]!.click();
      button('Groceries').click();
      button(splitType === 'UNEQUAL' ? 'Unequal' : 'Percent').click();
      fixture.detectChanges();
      expect(payerButtons.map((b) => b!.getAttribute('aria-pressed'))).toEqual([
        'false',
        'false',
        'true',
      ]);
      expect(button('Groceries').getAttribute('aria-pressed')).toBe('true');
      expect(button('Food & drink').getAttribute('aria-pressed')).toBe('false');
      const inputs = members.map((m) =>
        root.querySelector<HTMLInputElement>(
          `input[aria-label="${payerName(m)} ${splitType === 'UNEQUAL' ? 'exact amount in USD' : 'share in percent'}"]`,
        ),
      );
      expect(inputs.every(Boolean)).toBe(true);
      const fill = (input: HTMLInputElement, value: string) => {
        input.value = value;
        input.dispatchEvent(new Event('input', { bubbles: true }));
        fixture.detectChanges();
      };
      fill(root.querySelector('#ne-desc')!, 'Shared groceries');
      fill(root.querySelector('#ne-amt')!, '12.34');
      const submit = root.querySelector<HTMLButtonElement>('button[type=submit]')!;
      fill(inputs[0]!, splitType === 'UNEQUAL' ? '4.11' : '30');
      fill(inputs[1]!, splitType === 'UNEQUAL' ? '4.11' : '30');
      expect(submit.disabled).toBe(true);
      fill(inputs[2]!, splitType === 'UNEQUAL' ? '4.12' : '40');
      expect(submit.disabled).toBe(false);
      root
        .querySelector('form')!
        .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      const save = http.expectOne({ method: 'POST', url: `/api/groups/${group.id}/expenses` });
      expect(save.request.body).toMatchObject({
        amountMinor: 1234,
        currency: 'USD' as const,
        paidByUserId: 'alex-three',
        category: 'GROCERIES',
        splitType,
        splits: members.map((m, i) => ({
          userId: m.id,
          value: splitType === 'UNEQUAL' ? [411, 411, 412][i] : [3000, 3000, 4000][i],
        })),
      });
      save.flush(expense);
      http.expectOne('/api/dashboard').flush(emptyOverview);
    },
  );

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
  afterEach(() => {
    try {
      settleProfileRead(http);
      http.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  function open(type: 'UNEQUAL' | 'PERCENTAGE', values: string[], edit = false) {
    TestBed.inject(ModalService).openExpense(group.id, edit ? expense.id : undefined);
    const fixture = TestBed.createComponent(NewExpenseModalComponent);
    http.expectOne(`/api/groups/${group.id}`).flush({ ...group, members });
    if (edit) {
      http.expectOne(`/api/groups/${group.id}/expenses/${expense.id}`).flush({
        ...expense,
        splitType: 'PERCENTAGE',
        participants: [
          { user: me, shareMinor: 333 },
          { user: other, shareMinor: 667 },
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
      body.splitType === 'PERCENTAGE' ? 10000 : body.amountMinor,
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

    it(`retains explicitly entered zero ${type} inputs, including values rounded to zero`, () => {
      const { fixture } = open(type, ['100', '0', '0.001']);
      expect(fixture.componentInstance.splitValid()).toBe(true);
      expect(submit(fixture).splits).toEqual([
        { userId: me.id, value: 10000 },
        { userId: other.id, value: 0 },
        { userId: third.id, value: 0 },
      ]);
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

describe('original split intent in the rendered editor', () => {
  let http: HttpTestingController;
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
  afterEach(() => {
    try {
      settleProfileRead(http);
      http.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('keeps original 50/50 percentages for a one-cent expense and recalculates after an amount change', () => {
    TestBed.inject(ModalService).openExpense(group.id, expense.id);
    const fixture = TestBed.createComponent(NewExpenseModalComponent);
    http.expectOne(`/api/groups/${group.id}`).flush({ ...group, members: [me, other, third] });
    http.expectOne(`/api/groups/${group.id}/expenses/${expense.id}`).flush({
      ...expense,
      amountMinor: 1,
      currency: 'USD' as const,
      splitType: 'PERCENTAGE',
      splits: [
        { userId: me.id, value: 5000 },
        { userId: other.id, value: 5000 },
      ],
      participants: [
        { user: me, shareMinor: 1 },
        { user: other, shareMinor: 0 },
      ],
    });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelectorAll('.seg button')[2].getAttribute('aria-pressed')).toBe('true');
    expect([...root.querySelectorAll<HTMLInputElement>('.prow__val')].map((i) => i.value)).toEqual([
      '50.00',
      '50.00',
      '',
    ]);
    const amount = root.querySelector<HTMLInputElement>('#ne-amt')!;
    amount.value = '1';
    amount.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    expect(root.querySelector<HTMLButtonElement>('[type=submit]')!.disabled).toBe(false);
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    const save = http.expectOne({
      method: 'PUT',
      url: `/api/groups/${group.id}/expenses/${expense.id}`,
    });
    expect(save.request.body).toMatchObject({
      amountMinor: 100,
      currency: 'USD' as const,
      splitType: 'PERCENTAGE',
      splits: [
        { userId: me.id, value: 5000 },
        { userId: other.id, value: 5000 },
      ],
    });
    save.flush(expense);
    http.expectOne('/api/dashboard').flush(emptyOverview);
  });
  it('preserves an original zero percentage participant without adding other group members', () => {
    TestBed.inject(ModalService).openExpense(group.id, expense.id);
    const fixture = TestBed.createComponent(NewExpenseModalComponent);
    http.expectOne(`/api/groups/${group.id}`).flush({ ...group, members: [me, other, third] });
    http.expectOne(`/api/groups/${group.id}/expenses/${expense.id}`).flush({
      ...expense,
      splitType: 'PERCENTAGE',
      splits: [
        { userId: me.id, value: 10000 },
        { userId: other.id, value: 0 },
      ],
      participants: [
        { user: me, shareMinor: 1000 },
        { user: other, shareMinor: 0 },
      ],
    });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    const save = http.expectOne({
      method: 'PUT',
      url: `/api/groups/${group.id}/expenses/${expense.id}`,
    });
    expect(save.request.body.splits).toEqual([
      { userId: me.id, value: 10000 },
      { userId: other.id, value: 0 },
    ]);
    save.flush(expense);
    http.expectOne('/api/dashboard').flush(emptyOverview);
  });

  it('accepts positive integer weights and serializes units without dollar conversion', () => {
    TestBed.inject(ModalService).openExpense(group.id);
    const fixture = TestBed.createComponent(NewExpenseModalComponent);
    http.expectOne(`/api/groups/${group.id}`).flush(group);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    for (const [selector, value] of [
      ['#ne-desc', 'Weighted'],
      ['#ne-amt', '0.05'],
    ]) {
      const input = root.querySelector<HTMLInputElement>(selector)!;
      input.value = value;
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }
    [...root.querySelectorAll<HTMLButtonElement>('.seg button')]
      .find((b) => b.textContent?.trim() === 'Weights')!
      .click();
    fixture.detectChanges();
    const inputs = root.querySelectorAll<HTMLInputElement>('.prow__val');
    expect(inputs[0].getAttribute('aria-label')).toContain('weight in whole units');
    for (const [i, value] of ['1', '2'].entries()) {
      inputs[i].value = value;
      inputs[i].dispatchEvent(new Event('input', { bubbles: true }));
    }
    fixture.detectChanges();
    expect(root.querySelector<HTMLButtonElement>('[type=submit]')!.disabled).toBe(false);
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    const save = http.expectOne({ method: 'POST', url: `/api/groups/${group.id}/expenses` });
    expect(save.request.body).toMatchObject({
      splitType: 'WEIGHTS',
      amountMinor: 5,
      currency: 'USD' as const,
      splits: [
        { userId: me.id, value: 1 },
        { userId: other.id, value: 2 },
      ],
    });
    save.flush(expense);
    http.expectOne('/api/dashboard').flush(emptyOverview);
  });

  it('edits original signed adjustments and blocks an amount that would create negative shares', () => {
    TestBed.inject(ModalService).openExpense(group.id, expense.id);
    const fixture = TestBed.createComponent(NewExpenseModalComponent);
    http.expectOne(`/api/groups/${group.id}`).flush({ ...group, members: [me, other, third] });
    http.expectOne(`/api/groups/${group.id}/expenses/${expense.id}`).flush({
      ...expense,
      amountMinor: 200,
      currency: 'USD' as const,
      splitType: 'ADJUSTMENTS',
      splits: [
        { userId: me.id, value: -50 },
        { userId: other.id, value: 50 },
      ],
      participants: [
        { user: me, shareMinor: 50 },
        { user: other, shareMinor: 150 },
      ],
    });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    const selected = root.querySelector<HTMLButtonElement>('.seg button[aria-pressed=true]')!;
    expect(selected.textContent?.trim()).toBe('Adjustments');
    const inputs = root.querySelectorAll<HTMLInputElement>('.prow__val');
    expect(inputs[0].value).toBe('-0.50');
    expect(inputs[0].getAttribute('aria-label')).toContain('adjustment in signed USD');
    const amount = root.querySelector<HTMLInputElement>('#ne-amt')!;
    amount.value = '0.50';
    amount.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    expect(root.querySelector<HTMLButtonElement>('[type=submit]')!.disabled).toBe(true);
    expect(root.querySelector('.running')!.textContent).toContain('nonnegative');
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    http.expectNone((r) => r.method === 'PUT');
    amount.value = '2';
    amount.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    expect(root.querySelector<HTMLButtonElement>('[type=submit]')!.disabled).toBe(false);
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    const save = http.expectOne({
      method: 'PUT',
      url: `/api/groups/${group.id}/expenses/${expense.id}`,
    });
    expect(save.request.body).toMatchObject({
      splitType: 'ADJUSTMENTS',
      splits: [
        { userId: me.id, value: -50 },
        { userId: other.id, value: 50 },
      ],
    });
    save.flush(expense);
    http.expectOne('/api/dashboard').flush(emptyOverview);
  });

  it.each(['0', '-1', '1.5', '1000000000001'])(
    'blocks an invalid weight %s before POST',
    (invalid) => {
      TestBed.inject(ModalService).openExpense(group.id);
      const fixture = TestBed.createComponent(NewExpenseModalComponent);
      http.expectOne(`/api/groups/${group.id}`).flush(group);
      fixture.detectChanges();
      const root: HTMLElement = fixture.nativeElement;
      for (const [selector, value] of [
        ['#ne-desc', 'Weighted'],
        ['#ne-amt', '1'],
      ]) {
        const input = root.querySelector<HTMLInputElement>(selector)!;
        input.value = value;
        input.dispatchEvent(new Event('input', { bubbles: true }));
      }
      [...root.querySelectorAll<HTMLButtonElement>('.seg button')]
        .find((b) => b.textContent?.trim() === 'Weights')!
        .click();
      fixture.detectChanges();
      const input = root.querySelector<HTMLInputElement>('.prow__val')!;
      input.value = invalid;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      fixture.detectChanges();
      expect(root.querySelector<HTMLButtonElement>('[type=submit]')!.disabled).toBe(true);
      root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
      http.expectNone((r) => r.method === 'POST');
    },
  );

  it('restores whole weights on edit and reuses them when the amount changes', () => {
    TestBed.inject(ModalService).openExpense(group.id, expense.id);
    const fixture = TestBed.createComponent(NewExpenseModalComponent);
    http.expectOne(`/api/groups/${group.id}`).flush({ ...group, members: [me, other, third] });
    http.expectOne(`/api/groups/${group.id}/expenses/${expense.id}`).flush({
      ...expense,
      amountMinor: 5,
      currency: 'USD' as const,
      splitType: 'WEIGHTS',
      splits: [
        { userId: me.id, value: 1 },
        { userId: other.id, value: 2 },
      ],
      participants: [
        { user: me, shareMinor: 2 },
        { user: other, shareMinor: 3 },
      ],
    });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect([...root.querySelectorAll<HTMLInputElement>('.prow__val')].map((i) => i.value)).toEqual([
      '1',
      '2',
      '',
    ]);
    const amount = root.querySelector<HTMLInputElement>('#ne-amt')!;
    amount.value = '0.10';
    amount.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    const save = http.expectOne({
      method: 'PUT',
      url: `/api/groups/${group.id}/expenses/${expense.id}`,
    });
    expect(save.request.body).toMatchObject({
      splitType: 'WEIGHTS',
      amountMinor: 10,
      currency: 'USD' as const,
      splits: [
        { userId: me.id, value: 1 },
        { userId: other.id, value: 2 },
      ],
    });
    save.flush(expense);
    http.expectOne('/api/dashboard').flush(emptyOverview);
  });

  it('matches the server UUID remainder recipient for signed one-cent adjustments with payer excluded', () => {
    const low = { ...other, id: '00000000-0000-0000-0000-000000000001' };
    const high = { ...third, id: '80000000-0000-0000-0000-000000000002' };
    TestBed.inject(ModalService).openExpense(group.id, expense.id);
    const fixture = TestBed.createComponent(NewExpenseModalComponent);
    http.expectOne(`/api/groups/${group.id}`).flush({ ...group, members: [me, low, high] });
    http.expectOne(`/api/groups/${group.id}/expenses/${expense.id}`).flush({
      ...expense,
      amountMinor: 1,
      currency: 'USD' as const,
      splitType: 'ADJUSTMENTS',
      splits: [
        { userId: low.id, value: 1 },
        { userId: high.id, value: -1 },
      ],
      participants: [
        { user: low, shareMinor: 1 },
        { user: high, shareMinor: 0 },
      ],
    });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector<HTMLButtonElement>('[type=submit]')!.disabled).toBe(false);
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    const save = http.expectOne({
      method: 'PUT',
      url: `/api/groups/${group.id}/expenses/${expense.id}`,
    });
    expect(save.request.body.splits).toEqual([
      { userId: low.id, value: 1 },
      { userId: high.id, value: -1 },
    ]);
    save.flush(expense);
    http.expectOne('/api/dashboard').flush(emptyOverview);
  });
});
