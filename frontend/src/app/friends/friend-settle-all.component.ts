import { Component, computed, inject, input, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Subscription, takeUntil } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MoneyComponent } from '../shared/money.component';
import { SessionService } from '../core/auth/session.service';
import { DashboardService } from '../dashboard/dashboard.service';
import { FriendBalance, SettlementPreview } from './friend.models';
import { FriendService } from './friend.service';

/**
 * "Settle all in USD": previews every shared scope for one currency, then commits them in one
 * atomic request. The operation ID is kept through uncertain errors so a retry replays instead of
 * paying twice; a changed preview (409) replaces it.
 */
@Component({
  selector: 'app-friend-settle-all',
  imports: [MoneyComponent],
  templateUrl: './friend-settle-all.component.html',
  styleUrl: './personal-expenses.component.scss',
})
export class FriendSettleAllComponent {
  readonly friendId = input.required<string>();
  readonly friendName = input.required<string>();
  readonly balances = input.required<FriendBalance[]>();
  private readonly service = inject(FriendService);
  private readonly session = inject(SessionService);
  private readonly dashboard = inject(DashboardService);
  private request?: Subscription;

  protected readonly open = computed(() => this.balances().filter((b) => b.netMinor !== 0));
  protected readonly review = signal<SettlementPreview | null>(null);
  protected readonly busy = signal(false);
  protected readonly message = signal<string | null>(null);
  private operationId = crypto.randomUUID();

  constructor() {
    this.session.changes.pipe(takeUntilDestroyed()).subscribe(() => {
      this.request?.unsubscribe();
      this.review.set(null);
      this.busy.set(false);
      this.message.set(null);
    });
  }

  protected pays(payerId: string): string {
    return payerId === this.friendId()
      ? `${this.friendName()} pays you`
      : `You pay ${this.friendName()}`;
  }

  protected start(currency: string, notice: string | null = null): void {
    this.request?.unsubscribe();
    this.busy.set(true);
    this.message.set(notice);
    this.request = this.service
      .settlementPreview(this.friendId(), currency)
      .pipe(takeUntil(this.session.changes))
      .subscribe({
        next: (preview) => {
          this.busy.set(false);
          this.review.set(preview);
        },
        error: () => {
          this.busy.set(false);
          this.message.set('Could not load the settlement review. Try again.');
        },
      });
  }

  protected cancel(): void {
    this.request?.unsubscribe();
    this.review.set(null);
    this.busy.set(false);
    this.message.set(null);
  }

  protected confirm(preview: SettlementPreview): void {
    this.busy.set(true);
    this.message.set(null);
    this.request = this.service
      .settleAll(this.friendId(), preview.currency, preview.allocations, this.operationId)
      .pipe(takeUntil(this.session.changes))
      .subscribe({
        next: () => {
          this.operationId = crypto.randomUUID();
          this.busy.set(false);
          this.review.set(null);
          this.dashboard.refresh();
        },
        error: (err: HttpErrorResponse) => {
          if (err.status === 409) {
            // Balances moved (or the id was reused): nothing was written, so review afresh.
            this.operationId = crypto.randomUUID();
            this.start(preview.currency, 'Balances changed — review the settlement again.');
          } else {
            this.busy.set(false);
            this.message.set(
              'Not confirmed. Confirm again to retry safely; it will not be recorded twice.',
            );
          }
        },
      });
  }
}
