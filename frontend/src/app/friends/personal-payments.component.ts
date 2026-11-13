import { Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable, Subscription, takeUntil } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MoneyComponent } from '../shared/money.component';
import { SessionService } from '../core/auth/session.service';
import { DashboardService } from '../dashboard/dashboard.service';
import { ModalService } from '../modals/modal.service';
import {
  DeletedPayment,
  LedgerRevision,
  PaymentMethod,
  PaymentResponse,
} from '../payments/payment.models';
import { FriendBalance } from './friend.models';
import { FriendService } from './friend.service';
import { TranslatePipe } from '../core/i18n/translate.pipe';

/**
 * Settlements with one saved friend outside any group (G02.3): the personal balance with its
 * settle-up entry, and the pair's payment history with undo, restore and revisions.
 */
@Component({
  selector: 'app-personal-payments',
  imports: [TranslatePipe, MoneyComponent, RouterLink],
  templateUrl: './personal-payments.component.html',
  styleUrl: './personal-expenses.component.scss',
})
export class PersonalPaymentsComponent {
  readonly friendId = input.required<string>();
  readonly friendName = input.required<string>();
  readonly balances = input.required<FriendBalance[]>();
  readonly groups = input.required<{ id: string; name: string; balances: FriendBalance[] }[]>();
  private readonly service = inject(FriendService);
  private readonly session = inject(SessionService);
  private readonly dashboard = inject(DashboardService);
  private readonly modal = inject(ModalService);
  private readonly destroyRef = inject(DestroyRef);
  private request?: Subscription;
  private deletedRequest?: Subscription;
  private revisionsRequest?: Subscription;

  protected readonly payments = signal<PaymentResponse[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly busy = signal<string | null>(null);
  protected readonly deletedOpen = signal(false);
  protected readonly deleted = signal<DeletedPayment[]>([]);
  protected readonly revisionsOf = signal<string | null>(null);
  protected readonly revisions = signal<LedgerRevision<PaymentResponse>[]>([]);

  constructor() {
    effect(() => {
      this.friendId();
      untracked(() => this.load());
    });
    this.dashboard.refreshes.pipe(takeUntilDestroyed()).subscribe(() => {
      this.load();
      if (this.deletedOpen()) this.loadDeleted();
    });
    this.session.changes.pipe(takeUntilDestroyed()).subscribe(() => {
      this.request?.unsubscribe();
      this.deletedRequest?.unsubscribe();
      this.revisionsRequest?.unsubscribe();
      this.payments.set([]);
      this.deleted.set([]);
      this.deletedOpen.set(false);
      this.revisionsOf.set(null);
    });
  }

  protected methodText(method: PaymentMethod): string {
    // CASH → Cash, BANK_TRANSFER → Bank transfer (same labels as the payments screen).
    const text = method.toLowerCase().replace('_', ' ');
    return text[0].toUpperCase() + text.slice(1);
  }

  protected load(): void {
    this.request?.unsubscribe();
    this.error.set(null);
    this.request = this.service
      .personalPayments(this.friendId())
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => this.payments.set(rows),
        error: () => this.error.set('Could not load personal payments. Try again.'),
      });
  }

  protected settle(balance: FriendBalance): void {
    this.modal.openSettle({
      friendId: this.friendId(),
      groupName: 'Personal',
      counterparty: { id: this.friendId(), displayName: this.friendName(), email: null },
      netMinor: balance.netMinor,
      currency: balance.currency,
    });
  }

  protected undo(p: PaymentResponse): void {
    this.act(p.id, this.service.deletePersonalPayment(this.friendId(), p.id, p.version));
  }

  protected restore(d: DeletedPayment): void {
    this.act(
      d.payment.id,
      this.service.restorePersonalPayment(this.friendId(), d.payment.id, d.payment.version),
    );
  }

  private act(id: string, call: Observable<unknown>): void {
    this.busy.set(id);
    this.actionError.set(null);
    call.pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.busy.set(null);
        this.dashboard.refresh();
      },
      error: (err) => {
        this.busy.set(null);
        this.actionError.set(
          err?.error?.message ?? 'Could not change this payment. Reload and try again.',
        );
      },
    });
  }

  protected toggleDeleted(): void {
    if (this.deletedOpen()) {
      this.deletedRequest?.unsubscribe();
      this.deletedOpen.set(false);
      return;
    }
    this.deletedOpen.set(true);
    this.loadDeleted();
  }

  private loadDeleted(): void {
    this.deletedRequest?.unsubscribe();
    this.deletedRequest = this.service
      .deletedPersonalPayments(this.friendId())
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => this.deleted.set(rows),
        error: () => this.actionError.set('Could not load undone payments. Try again.'),
      });
  }

  protected toggleRevisions(p: PaymentResponse): void {
    if (this.revisionsOf() === p.id) {
      this.revisionsOf.set(null);
      return;
    }
    this.revisionsOf.set(p.id);
    this.revisions.set([]);
    // Only the newest request may fill the list, or a slow earlier one shows the wrong payment.
    this.revisionsRequest?.unsubscribe();
    this.revisionsRequest = this.service
      .personalPaymentHistory(this.friendId(), p.id)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => this.revisions.set(rows),
        error: () => this.actionError.set('Could not load revisions. Try again.'),
      });
  }
}
