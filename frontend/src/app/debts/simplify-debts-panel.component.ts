import { Component, effect, inject, input, output, signal } from '@angular/core';
import { centsToDisplay } from '../expenses/expense.service';
import { SettlePrefill } from '../payments/payment.models';
import { SuggestedTransaction } from './debt.models';
import { DebtService } from './debt.service';

/**
 * Shows the minimal set of transfers that settles the group (from the debt-simplification
 * endpoint). Each row can launch the existing settle-up form pre-filled with that exact
 * from/to/amount. Reloads whenever `refreshKey` changes (same idiom as BalancePanelComponent).
 */
@Component({
  selector: 'app-simplify-debts-panel',
  imports: [],
  templateUrl: './simplify-debts-panel.component.html',
  styleUrl: './simplify-debts-panel.component.scss',
})
export class SimplifyDebtsPanelComponent {
  readonly groupId = input.required<string>();
  readonly refreshKey = input<number>(0);
  /** Emitted when a suggestion's "Settle up" is clicked; the parent prefills the settle-up form. */
  readonly settleTransaction = output<SettlePrefill>();

  private readonly debtService = inject(DebtService);

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly transactions = signal<SuggestedTransaction[]>([]);
  protected readonly display = centsToDisplay;

  constructor() {
    // Load on init and whenever the parent bumps refreshKey (e.g. after a payment).
    effect(() => {
      this.refreshKey();
      this.load();
    });
  }

  private load(): void {
    this.loading.set(true);
    this.debtService.getSimplifiedDebts(this.groupId()).subscribe({
      next: (res) => {
        this.transactions.set(res.transactions);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load simplified debts.');
        this.loading.set(false);
      },
    });
  }

  settle(t: SuggestedTransaction): void {
    this.settleTransaction.emit({
      kind: 'transaction',
      payerUserId: t.from.id,
      payeeUserId: t.to.id,
      amountCents: t.amountCents,
    });
  }

  /** Up to two initials for an avatar, e.g. "Ada Lovelace" → "AL". */
  initials(name: string): string {
    return name
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]?.toUpperCase() ?? '')
      .join('');
  }
}
