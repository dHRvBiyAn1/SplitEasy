import { Component, OnInit, inject, input, signal } from '@angular/core';
import { MatListModule } from '@angular/material/list';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MemberBalance } from './balance.models';
import { BalanceService, describeBalance } from './balance.service';

/**
 * Shows each member's net position for a group. Reloads whenever `refreshKey`
 * changes (the parent bumps it after an expense is added) so balances stay live.
 */
@Component({
  selector: 'app-balance-panel',
  imports: [MatListModule, MatProgressBarModule],
  templateUrl: './balance-panel.component.html',
  styleUrl: './balance-panel.component.scss',
})
export class BalancePanelComponent implements OnInit {
  readonly groupId = input.required<string>();
  readonly refreshKey = input<number>(0);

  private readonly balanceService = inject(BalanceService);

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly balances = signal<MemberBalance[]>([]);
  protected readonly describe = describeBalance;

  private lastLoadedKey = -1;

  ngOnInit(): void {
    this.load();
  }

  ngDoCheck(): void {
    // input() signals can change after init; reload when the parent bumps the key.
    if (this.refreshKey() !== this.lastLoadedKey) {
      this.load();
    }
  }

  private load(): void {
    this.lastLoadedKey = this.refreshKey();
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
