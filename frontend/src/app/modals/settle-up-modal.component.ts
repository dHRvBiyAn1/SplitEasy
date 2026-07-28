import { Component, computed, effect, inject, signal } from '@angular/core';
import { AuthService } from '../core/auth/auth.service';
import { DashboardService } from '../dashboard/dashboard.service';
import { Settlement } from '../dashboard/dashboard.models';
import { DebtService } from '../debts/debt.service';
import { SimplifiedDebtsResponse } from '../debts/debt.models';
import { centsToDisplay, dollarsToCents } from '../expenses/expense.service';
import { PaymentService } from '../payments/payment.service';
import { avatarTint } from '../core/avatar';
import { ModalService } from './modal.service';
import { ModalShellComponent } from './modal-shell.component';

@Component({
  selector: 'app-settle-up-modal',
  imports: [ModalShellComponent],
  templateUrl: './settle-up-modal.component.html',
  styleUrl: './modals.scss',
})
export class SettleUpModalComponent {
  private readonly dashboard = inject(DashboardService);
  private readonly payments = inject(PaymentService);
  private readonly debts = inject(DebtService);
  private readonly auth = inject(AuthService);
  private readonly modal = inject(ModalService);

  protected readonly display = centsToDisplay;

  /** Simplify is ON by default: open on the fewest payments that clear the debts. */
  protected readonly simplify = signal(true);
  /** Simplified rows once fetched (null = not loaded yet). */
  private readonly simplified = signal<Settlement[] | null>(null);
  protected readonly loadingSimplified = signal(false);

  /** Every direct balance, scoped to one group when opened from a group's detail page. */
  protected readonly direct = computed<Settlement[]>(() => {
    const all = this.dashboard.data()?.settlements ?? [];
    const gid = this.modal.settleGroupId();
    return gid ? all.filter((s) => s.groupId === gid) : all;
  });

  /** What the list shows: the minimal set of payments, or every direct balance. */
  protected readonly settlements = computed<Settlement[]>(() =>
    this.simplify() ? (this.simplified() ?? []) : this.direct(),
  );

  /** Payments saved by simplifying (0 when it doesn't help — the banner hides the line). */
  protected readonly saved = computed(() =>
    Math.max(0, this.direct().length - (this.simplified()?.length ?? 0)),
  );

  protected readonly selected = signal<Settlement | null>(this.modal.settlePrefill());
  /** Editable amount for the selected row, in dollars — settle in full or partially. */
  protected readonly amount = signal('');
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    // The group list arrives with the dashboard payload, so load once it's there.
    effect(() => {
      const ids = this.scopeGroupIds();
      if (ids.length > 0 && this.simplified() === null && !this.loadingSimplified()) {
        this.loadSimplified(ids);
      }
    });

    const prefill = this.modal.settlePrefill();
    if (prefill) {
      this.amount.set(centsToDisplay(Math.abs(prefill.netCents)));
    }
  }

  /** Groups to simplify: just this one when opened from a group, else all of mine. */
  private scopeGroupIds(): string[] {
    const gid = this.modal.settleGroupId();
    if (gid) {
      return [gid];
    }
    return (this.dashboard.data()?.groups ?? []).map((g) => g.id);
  }

  private loadSimplified(groupIds: string[]): void {
    this.loadingSimplified.set(true);
    this.debts.getSimplifiedDebtsForGroups(groupIds).subscribe({
      next: (responses) => {
        this.simplified.set(responses.flatMap((r) => this.toSettlements(r)));
        this.loadingSimplified.set(false);
      },
      error: () => {
        // Fall back to the direct balances rather than showing an empty modal.
        this.simplified.set(null);
        this.simplify.set(false);
        this.loadingSimplified.set(false);
      },
    });
  }

  /**
   * Keeps only the suggested transfers I'm part of (the others aren't mine to record) and
   * maps them onto the Settlement shape the list/confirm views already speak:
   * netCents > 0 = they pay me, < 0 = I pay them.
   */
  private toSettlements(res: SimplifiedDebtsResponse): Settlement[] {
    const me = this.auth.user()?.id ?? '';
    const groupName = this.groupName(res.groupId);
    return res.transactions
      .filter((t) => t.from.id === me || t.to.id === me)
      .map((t) =>
        t.to.id === me
          ? { groupId: res.groupId, groupName, counterparty: t.from, netCents: t.amountCents }
          : { groupId: res.groupId, groupName, counterparty: t.to, netCents: -t.amountCents },
      );
  }

  private groupName(groupId: string): string {
    return (this.dashboard.data()?.groups ?? []).find((g) => g.id === groupId)?.name ?? '';
  }

  toggleSimplify(): void {
    this.simplify.set(!this.simplify());
  }

  initials(name: string): string {
    return name
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]?.toUpperCase() ?? '')
      .join('');
  }

  tint(id: string): { background: string; color: string } {
    return avatarTint(id);
  }

  /** They owe me (net>0) → "Name owes you"; I owe them (net<0) → "You owe Name". */
  label(s: Settlement): string {
    const name = s.counterparty.displayName.split(/\s+/)[0];
    return s.netCents > 0 ? `${name} owes you` : `You owe ${name}`;
  }

  /** Confirm-step lead: who is paying whom, mirroring the row's direction. */
  payingLabel(s: Settlement): string {
    const name = s.counterparty.displayName.split(/\s+/)[0];
    return s.netCents > 0 ? `${name} is paying you` : `You are paying ${name}`;
  }

  select(s: Settlement): void {
    this.error.set(null);
    this.amount.set(centsToDisplay(Math.abs(s.netCents)));
    this.selected.set(s);
  }

  onAmountInput(value: string): void {
    this.amount.set(value);
    this.error.set(null);
  }

  /** Entered dollars as integer cents; NaN/blank becomes 0 so validation rejects it. */
  protected enteredCents(): number {
    const n = Number(this.amount());
    return Number.isFinite(n) ? dollarsToCents(n) : 0;
  }

  cancel(): void {
    this.selected.set(null);
    this.error.set(null);
  }

  close(): void {
    this.modal.close();
  }

  confirm(): void {
    const s = this.selected();
    if (!s || this.saving()) {
      return;
    }
    const cents = this.enteredCents();
    if (cents <= 0) {
      this.error.set('Enter an amount greater than zero.');
      return;
    }
    const me = this.auth.user()?.id ?? '';
    // net>0: they owe me → they pay me. net<0: I owe them → I pay them.
    const [payer, payee] = s.netCents > 0 ? [s.counterparty.id, me] : [me, s.counterparty.id];
    this.saving.set(true);
    this.payments
      .recordPayment(s.groupId, { payerUserId: payer, payeeUserId: payee, amountCents: cents })
      .subscribe({
        next: () => {
          this.dashboard.refresh();
          this.modal.close();
        },
        error: (err) => {
          this.error.set(err?.error?.message ?? 'Could not record the payment.');
          this.saving.set(false);
        },
      });
  }
}
