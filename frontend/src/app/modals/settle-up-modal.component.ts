import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DestroyRef, Component, computed, inject, signal } from '@angular/core';
import { takeUntil } from 'rxjs';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';
import { DashboardService } from '../dashboard/dashboard.service';
import { Settlement } from '../dashboard/dashboard.models';
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
  private readonly auth = inject(AuthService);
  private readonly modal = inject(ModalService);
  private readonly session = inject(SessionService);
  private readonly sessionVersion = this.session.version;
  private readonly destroyRef = inject(DestroyRef);

  protected readonly display = centsToDisplay;

  /** The dashboard already projects the selected mode, including all settlement actions. */
  protected readonly settlements = computed<Settlement[]>(() => {
    const all = this.dashboard.data()?.settlements ?? [];
    const gid = this.modal.settleGroupId();
    return gid ? all.filter((s) => s.groupId === gid) : all;
  });
  protected readonly loading = this.dashboard.loading;
  protected readonly loadFailed = this.dashboard.error;
  protected readonly allSimplified = computed(() => {
    const gid = this.modal.settleGroupId();
    const groups = (this.dashboard.data()?.groups ?? []).filter((g) => !gid || g.id === gid);
    return groups.length > 0 && groups.every((g) => g.simplifyDebts);
  });

  protected readonly selected = signal<Settlement | null>(this.modal.settlePrefill());
  /** Editable amount for the selected row, in dollars — settle in full or partially. */
  protected readonly amount = signal('');
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.destroyRef.onDestroy(() => {
      if (this.session.version === this.sessionVersion) this.modal.setMutationPending(false);
    });
    const prefill = this.modal.settlePrefill();
    if (prefill) {
      this.amount.set(centsToDisplay(Math.abs(prefill.netCents)));
    }
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
    const name = `${s.counterparty.displayName} (${s.counterparty.email})`;
    return s.netCents > 0 ? `${name} owes you` : `You owe ${name}`;
  }

  /** Confirm-step lead: who is paying whom, mirroring the row's direction. */
  payingLabel(s: Settlement): string {
    const name = `${s.counterparty.displayName} (${s.counterparty.email})`;
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
    if (this.saving()) return;
    this.selected.set(null);
    this.error.set(null);
  }

  close(): void {
    if (this.session.version !== this.sessionVersion) return;
    this.modal.requestClose();
  }

  confirm(): void {
    if (this.session.version !== this.sessionVersion) return;
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
    this.modal.setMutationPending(true);
    this.payments
      .recordPayment(s.groupId, { payerUserId: payer, payeeUserId: payee, amountCents: cents })
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.dashboard.refresh();
          this.modal.close();
        },
        error: (err) => {
          this.error.set(err?.error?.message ?? 'Could not record the payment.');
          this.saving.set(false);
          this.modal.setMutationPending(false);
        },
      });
  }
}
