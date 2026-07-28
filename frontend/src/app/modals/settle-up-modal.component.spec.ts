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
      simplifyDebts: true,
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
  let data: ReturnType<typeof signal<DashboardResponse>>;

  function rows(): HTMLElement[] {
    return Array.from(fixture.nativeElement.querySelectorAll('.srow'));
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
    data = signal(dashboardData);

    TestBed.configureTestingModule({
      imports: [SettleUpModalComponent],
      providers: [
        { provide: DashboardService, useValue: { data, refresh: vi.fn() } },
        { provide: DebtService, useValue: { getSimplifiedDebtsForGroups: getForGroups } },
        { provide: PaymentService, useValue: { recordPayment } },
        { provide: AuthService, useValue: { user: signal(me) } },
      ],
    });
    modal = TestBed.inject(ModalService);
    modal.openSettle();
  });

  it('shows only the minimal set of payments when the group asks to simplify', async () => {
    await render();

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

  it('lists every direct balance when the group has simplify turned off', async () => {
    // The group's Advanced setting drives this now — there is no toggle in the modal.
    data.set({
      ...dashboardData,
      groups: [{ ...dashboardData.groups[0], simplifyDebts: false }],
    } as DashboardResponse);
    await render();

    expect(fixture.nativeElement.querySelector('.simplify-toggle')).toBeNull();
    expect(rows()).toHaveLength(2);
    expect(fixture.nativeElement.textContent).toContain('Priya owes you');
    expect(fixture.nativeElement.textContent).toContain('You owe Dan');
  });

  it('simplifies only the opened group when launched from a group page', async () => {
    modal.openSettle(undefined, 'g1');
    await render();

    expect(getForGroups).toHaveBeenCalledWith(['g1']);
  });

  it('records a partial amount instead of the full balance', async () => {
    await render();
    rows()[0].click();
    fixture.detectChanges();

    const input: HTMLInputElement = fixture.nativeElement.querySelector('.settle-amount__input');
    expect(input.value).toBe('30.00'); // prefilled with the full amount

    input.value = '12.50';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.dc-btn-accent').click();

    expect(recordPayment).toHaveBeenCalledWith('g1', {
      payerUserId: priya.id, // she owes me, so she pays
      payeeUserId: me.id,
      amountCents: 1250,
    });
  });

  it('rejects a non-positive amount', async () => {
    await render();
    rows()[0].click();
    fixture.detectChanges();

    const input: HTMLInputElement = fixture.nativeElement.querySelector('.settle-amount__input');
    input.value = '0';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.dc-btn-accent').click();
    fixture.detectChanges();

    expect(recordPayment).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('.mform__error').textContent).toContain(
      'greater than zero',
    );
  });
});
