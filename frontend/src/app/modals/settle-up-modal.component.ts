import { Component, inject, signal } from '@angular/core';
import { AuthService } from '../core/auth/auth.service';
import { DashboardService } from '../dashboard/dashboard.service';
import { Settlement } from '../dashboard/dashboard.models';
import { centsToDisplay } from '../expenses/expense.service';
import { PaymentService } from '../payments/payment.service';
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

  protected readonly display = centsToDisplay;
  protected readonly settlements = () => this.dashboard.data()?.settlements ?? [];
  protected readonly selected = signal<Settlement | null>(this.modal.settlePrefill());
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  /** They owe me (net>0) → "Name owes you"; I owe them (net<0) → "You owe Name". */
  label(s: Settlement): string {
    const name = s.counterparty.displayName.split(/\s+/)[0];
    return s.netCents > 0 ? `${name} owes you` : `You owe ${name}`;
  }

  select(s: Settlement): void {
    this.error.set(null);
    this.selected.set(s);
  }

  cancel(): void {
    this.selected.set(null);
  }

  close(): void {
    this.modal.close();
  }

  confirm(): void {
    const s = this.selected();
    if (!s || this.saving()) {
      return;
    }
    const me = this.auth.user()?.id ?? '';
    // net>0: they owe me → they pay me. net<0: I owe them → I pay them.
    const [payer, payee] = s.netCents > 0 ? [s.counterparty.id, me] : [me, s.counterparty.id];
    this.saving.set(true);
    this.payments
      .recordPayment(s.groupId, { payerUserId: payer, payeeUserId: payee, amountCents: Math.abs(s.netCents) })
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
