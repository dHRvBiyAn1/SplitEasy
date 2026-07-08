import { Component, effect, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatListModule } from '@angular/material/list';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { SettlePrefill } from '../payments/payment.models';
import { MemberBalance } from './balance.models';
import { BalanceService, describeBalance } from './balance.service';

/**
 * Shows each member's net position for a group. Reloads whenever `refreshKey`
 * changes (the parent bumps it after an expense or payment changes) so balances stay live.
 */
@Component({
  selector: 'app-balance-panel',
  imports: [MatListModule, MatProgressBarModule, MatButtonModule],
  templateUrl: './balance-panel.component.html',
  styleUrl: './balance-panel.component.scss',
})
export class BalancePanelComponent {
  readonly groupId = input.required<string>();
  readonly refreshKey = input<number>(0);
  /** Emitted when a member's "Settle up" is clicked; the parent prefills the settle-up form. */
  readonly settleWith = output<SettlePrefill>();

  private readonly balanceService = inject(BalanceService);

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly balances = signal<MemberBalance[]>([]);
  protected readonly describe = describeBalance;

  constructor() {
    // Load on init and whenever groupId or the parent's refreshKey changes.
    effect(() => {
      this.refreshKey(); // tracked so a bump triggers a reload
      this.load();
    });
  }

  private load(): void {
    this.loading.set(true);
    this.balanceService.getBalances(this.groupId()).subscribe({
      next: (res) => {
        this.balances.set(res.balances);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load balances.');
        this.loading.set(false);
      },
    });
  }

  settle(balance: MemberBalance): void {
    this.settleWith.emit({ kind: 'balance', userId: balance.user.id, netCents: balance.netCents });
  }

  balanceClass(netCents: number): string {
    if (netCents > 0) {
      return 'owed';
    }
    if (netCents < 0) {
      return 'owes';
    }
    return 'settled';
  }
}
