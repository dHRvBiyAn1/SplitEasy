import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../core/auth/auth.service';
import { DashboardService } from '../dashboard/dashboard.service';
import { DashboardResponse } from '../dashboard/dashboard.models';
import { DebtService } from '../debts/debt.service';
import { PaymentService } from '../payments/payment.service';
import { ModalService } from './modal.service';
import { SettleUpModalComponent } from './settle-up-modal.component';

const me = { id: 'me', email: 'me@ex.com', displayName: 'Me Myself' };
const priya = { id: 'p1', email: 'p@ex.com', displayName: 'Priya Shah' };
const dan = { id: 'd1', email: 'd@ex.com', displayName: 'Dan Kim' };

/** Two direct balances in one group; simplification collapses them to a single payment. */
const dashboardData = {
  totalNetCents: 3000,
  owedCents: 5000,
  owedPeopleCount: 1,
  oweCents: 2000,
  owePeopleCount: 1,
  groupCount: 1,
  groups: [
    {
      id: 'g1',
      name: 'Maple St. House',
      type: 'HOME',
      memberCount: 3,
      totalSpentCents: 10000,
      youAreOwedCents: 5000,
      youOweCents: 2000,
      netCents: 3000,
    },
  ],
  people: [],
  settlements: [
    { groupId: 'g1', groupName: 'Maple St. House', counterparty: priya, netCents: 5000 },
    { groupId: 'g1', groupName: 'Maple St. House', counterparty: dan, netCents: -2000 },
  ],
  activity: [],
} as unknown as DashboardResponse;

const simplifiedForG1 = {
  groupId: 'g1',
  transactions: [
    { from: priya, to: me, amountCents: 3000 }, // mine to record
    { from: dan, to: priya, amountCents: 1000 }, // between two other members
  ],
};

describe('SettleUpModalComponent', () => {
  let fixture: ComponentFixture<SettleUpModalComponent>;
  let modal: ModalService;
  let recordPayment: ReturnType<typeof vi.fn>;
  let getForGroups: ReturnType<typeof vi.fn>;

  function rows(): HTMLElement[] {
    return Array.from(fixture.nativeElement.querySelectorAll('.srow'));
  }

  function toggle(): HTMLElement {
    return fixture.nativeElement.querySelector('.simplify-toggle');
  }

  async function render(): Promise<void> {
    fixture = TestBed.createComponent(SettleUpModalComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(() => {
    recordPayment = vi.fn().mockReturnValue(of({}));
    getForGroups = vi.fn().mockReturnValue(of([simplifiedForG1]));

    TestBed.configureTestingModule({
      imports: [SettleUpModalComponent],
      providers: [
        { provide: DashboardService, useValue: { data: signal(dashboardData), refresh: vi.fn() } },
        { provide: DebtService, useValue: { getSimplifiedDebtsForGroups: getForGroups } },
        { provide: PaymentService, useValue: { recordPayment } },
        { provide: AuthService, useValue: { user: signal(me) } },
      ],
    });
    modal = TestBed.inject(ModalService);
    modal.openSettle();
  });

  it('opens with simplify on, showing only the minimal set of payments', async () => {
    await render();

    expect(toggle().getAttribute('aria-checked')).toBe('true');
    expect(rows()).toHaveLength(1);
    expect(rows()[0].textContent).toContain('Priya owes you');
    expect(rows()[0].textContent).toContain('30.00');
  });

  it('excludes suggested transfers between two other members', async () => {
    await render();

    // dan → priya is not mine to record, so it never becomes a row.
    expect(fixture.nativeElement.textContent).not.toContain('Dan');
  });

  it('reports how many payments simplifying saves', async () => {
    await render();

    expect(fixture.nativeElement.textContent).toContain('1 fewer payment');
  });

  it('lists every direct balance when simplify is switched off', async () => {
    await render();

    toggle().click();
    fixture.detectChanges();

    expect(toggle().getAttribute('aria-checked')).toBe('false');
    expect(rows()).toHaveLength(2);
    expect(fixture.nativeElement.textContent).toContain('Priya owes you');
    expect(fixture.nativeElement.textContent).toContain('You owe Dan');
  });

  it('simplifies only the opened group when launched from a group page', async () => {
    modal.openSettle(undefined, 'g1');
    await render();

    expect(getForGroups).toHaveBeenCalledWith(['g1']);
  });

});
