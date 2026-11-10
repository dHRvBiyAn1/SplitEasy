import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, Subject } from 'rxjs';
import { BalanceService } from '../balances/balance.service';
import { GroupBalancesResponse } from '../balances/balance.models';
import { PersonBalance } from '../dashboard/dashboard.models';
import { ExpenseSummary } from '../expenses/expense.models';
import { ExpenseService } from '../expenses/expense.service';
import { ModalService } from '../modals/modal.service';
import { GroupDetailComponent } from './group-detail.component';
import { GroupResponse } from './group.models';
import { GroupService } from './group.service';

const user = { id: 'me', displayName: 'Ada', email: 'ada@example.test' };
const payload = (id: string) => ({
  group: {
    id,
    name: `Group ${id}`,
    type: 'HOME' as const,
    createdBy: user,
    members: [user],
    simplifyDebts: true,
    createdAt: '',
  },
  balances: [{ user: { ...user, id: `person-${id}`, displayName: `Person${id}` }, netMinor: 1234 }],
  expenses: [
    {
      id: `expense-${id}`,
      description: `Dinner ${id}`,
      amountMinor: 2468,
      currency: 'USD' as const,
      paidBy: user,
      participantCount: 2,
      category: 'FOOD_DRINK' as const,
      spentOn: '2026-10-16',
      viewerDeltaMinor: 1234,
      createdAt: '',
    },
  ],
});
const reads = () => ({
  group: new Subject<GroupResponse>(),
  balances: new Subject<PersonBalance[]>(),
  ledger: new Subject<GroupBalancesResponse>(),
  expenses: new Subject<ExpenseSummary[]>(),
});

describe('group detail route ownership', () => {
  let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let pending: Record<string, ReturnType<typeof reads>>;

  beforeEach(() => {
    params = new BehaviorSubject(convertToParamMap({ id: 'a' }));
    pending = { a: reads(), b: reads() };
    TestBed.configureTestingModule({
      imports: [GroupDetailComponent],
      providers: [
        provideHttpClient(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: params } },
      ],
    });
    vi.spyOn(TestBed.inject(GroupService), 'getGroup').mockImplementation(
      (id) => pending[id].group,
    );
    vi.spyOn(TestBed.inject(BalanceService), 'getMyBalances').mockImplementation(
      (id) => pending[id].balances,
    );
    vi.spyOn(TestBed.inject(BalanceService), 'getBalances').mockImplementation(
      (id) => pending[id].ledger,
    );
    vi.spyOn(TestBed.inject(ExpenseService), 'listExpenses').mockImplementation(
      (id) => pending[id].expenses,
    );
  });

  function finish(id: string) {
    const data = payload(id);
    pending[id].expenses.next(data.expenses);
    pending[id].expenses.complete();
    pending[id].ledger.next({ groupId: id, balances: [] });
    pending[id].ledger.complete();
    pending[id].balances.next(data.balances);
    pending[id].balances.complete();
    pending[id].group.next(data.group);
    pending[id].group.complete();
  }

  it('resets loaded datasets immediately when the route changes', () => {
    const fixture = TestBed.createComponent(GroupDetailComponent);
    fixture.detectChanges();
    finish('a');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Dinner a');
    params.next(convertToParamMap({ id: 'b' }));
    expect(fixture.componentInstance['group']()).toBeNull();
    expect(fixture.componentInstance['myBalances']()).toEqual([]);
    expect(fixture.componentInstance['expenses']()).toEqual([]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[aria-label="Loading group"]')).not.toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Dinner a');
  });

  it.each(['success-before-b', 'success-after-b', 'error-after-b'])(
    'keeps rendered group and modal context on B after late A %s',
    (scenario) => {
      const fixture = TestBed.createComponent(GroupDetailComponent);
      fixture.detectChanges();
      pending['a'].group.next(payload('a').group);
      pending['a'].group.complete();
      params.next(convertToParamMap({ id: 'b' }));
      const lateA = () => {
        if (scenario === 'error-after-b') pending['a'].balances.error({ status: 403 });
        else finish('a');
      };
      if (scenario === 'success-before-b') lateA();
      pending['b'].expenses.next(payload('b').expenses);
      pending['b'].expenses.complete();
      pending['b'].group.next(payload('b').group);
      pending['b'].group.complete();
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.gd__head')).toBeNull();
      pending['b'].ledger.next({ groupId: 'b', balances: [] });
      pending['b'].ledger.complete();
      pending['b'].balances.next(payload('b').balances);
      pending['b'].balances.complete();
      if (scenario !== 'success-before-b') lateA();
      fixture.detectChanges();
      const page: HTMLElement = fixture.nativeElement;
      expect(page.querySelector('h1')?.textContent).toContain('Group b');
      expect(page.querySelector('.gd__pills')?.textContent).toContain('Personb owes you');
      expect(page.querySelector('.xrow__title')?.textContent).toContain('Dinner b');
      expect(page.textContent).not.toContain('Dinner a');
      expect(page.querySelector('[role="alert"]')).toBeNull();
      expect(page.querySelector('.gd__gear')?.getAttribute('href')).toBe('/groups/b/settings');
      page.querySelector<HTMLButtonElement>('.gd__actions .dc-btn-accent')!.click();
      const modal = TestBed.inject(ModalService);
      expect(modal.active()).toBe('expense');
      expect(modal.expenseGroupId()).toBe('b');
      page.querySelector<HTMLButtonElement>('.gd__actions .dc-btn-outline')!.click();
      expect(modal.active()).toBe('settle');
      expect(modal.settleGroupId()).toBe('b');
    },
  );

  it('tears down pending loads and stops listening for routes on destruction', () => {
    const fixture = TestBed.createComponent(GroupDetailComponent);
    fixture.detectChanges();
    expect(Object.values(pending['a']).every((request) => request.observed)).toBe(true);
    fixture.destroy();
    expect(Object.values(pending['a']).every((request) => !request.observed)).toBe(true);
    pending['a'].group.error({ status: 403 });
    params.next(convertToParamMap({ id: 'b' }));
    expect(Object.values(pending['b']).every((request) => !request.observed)).toBe(true);
    expect(fixture.componentInstance['groupId']).toBe('a');
    expect(fixture.componentInstance['error']()).toBeNull();
  });
});
