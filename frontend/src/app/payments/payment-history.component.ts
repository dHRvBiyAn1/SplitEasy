import { Component, DestroyRef, HostListener, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, Subscription, forkJoin, takeUntil } from 'rxjs';
import { MoneyComponent } from '../shared/money.component';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';
import { DashboardService } from '../dashboard/dashboard.service';
import { GroupService } from '../groups/group.service';
import { financialRoster } from '../groups/group.models';
import { ParticipantSummary } from '../groups/participant.models';
import { Currency, CURRENCY_EXPONENT } from '../shared/currency';
import {
  DeletedPayment,
  LedgerRevision,
  PAYMENT_METHOD_LABEL,
  PaymentMethod,
  PaymentResponse,
} from './payment.models';
import { PaymentService } from './payment.service';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { translate } from '../core/i18n/i18n.service';

const ACTION_LABEL: Record<LedgerRevision<PaymentResponse>['action'], string> = {
  IMPORTED: translate('paymentHistory.imported'),
  CREATED: translate('paymentHistory.recorded'),
  CORRECTED: translate('paymentHistory.corrected'),
  DELETED: translate('paymentHistory.undone'),
  RESTORED: translate('groupDetail.restored'),
};

/**
 * The group's settlement ledger: every recorded payment with its financial date, method, note and
 * recorder, and the entry points to correct one, undo and restore it, or review its revisions.
 */
@Component({
  selector: 'app-payment-history',
  imports: [TranslatePipe, RouterLink, MoneyComponent],
  templateUrl: './payment-history.component.html',
  styleUrl: './payment-history.component.scss',
})
export class PaymentHistoryComponent {
  private readonly service = inject(PaymentService);
  private readonly groupsApi = inject(GroupService);
  private readonly dashboard = inject(DashboardService);
  private readonly session = inject(SessionService);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly scopeChanges = new Subject<void>();
  private loadRequest?: Subscription;

  protected groupId = '';
  protected readonly payments = signal<PaymentResponse[]>([]);
  protected readonly undone = signal<DeletedPayment[]>([]);
  protected readonly people = signal<ParticipantSummary[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly methods = Object.keys(PAYMENT_METHOD_LABEL) as PaymentMethod[];
  protected readonly methodLabel = PAYMENT_METHOD_LABEL;
  protected readonly actionLabel = ACTION_LABEL;

  constructor() {
    this.dashboard.refreshes.pipe(takeUntilDestroyed()).subscribe(() => this.foreground());
    this.session.changes.pipe(takeUntilDestroyed()).subscribe(() => {
      this.reset();
      this.error.set(translate('paymentHistory.sessionChangedTryAgain'));
    });
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.scopeChanges.next();
      this.reset();
      this.groupId = params.get('id') ?? '';
      this.load();
    });
  }

  private reset() {
    this.loadRequest?.unsubscribe();
    this.payments.set([]);
    this.undone.set([]);
    this.people.set([]);
    this.editing.set(null);
    this.confirming.set(null);
    this.revisionsFor.set(null);
    this.revisions.set([]);
    this.saving.set(false);
    this.writeError.set(null);
    this.notice.set(null);
    this.loading.set(false);
    this.error.set(null);
  }

  @HostListener('window:focus')
  @HostListener('document:visibilitychange')
  protected foreground() {
    if (document.visibilityState === 'visible' && this.auth.isAuthenticated()) this.load();
  }

  /** Both reads land together: a half-loaded ledger must not look like a settled one. */
  protected load(force = false) {
    if (!this.groupId || this.saving() || (!force && this.loading())) return;
    if (!this.auth.isAuthenticated()) return;
    this.loadRequest?.unsubscribe();
    this.loading.set(true);
    this.error.set(null);
    this.loadRequest = forkJoin({
      active: this.service.listPayments(this.groupId),
      undone: this.service.listDeleted(this.groupId),
    })
      .pipe(
        takeUntil(this.scopeChanges),
        takeUntil(this.session.changes),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: ({ active, undone }) => {
          this.payments.set(active);
          this.undone.set(undone);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.payments.set([]);
          this.undone.set([]);
          this.error.set(translate('paymentHistory.couldNotLoadSettlements'));
        },
      });
  }

  // --- correction ---------------------------------------------------------
  protected readonly editing = signal<PaymentResponse | null>(null);
  protected readonly saving = signal(false);
  protected readonly writeError = signal<string | null>(null);
  protected payerId = '';
  protected payeeId = '';
  protected amount = '';
  protected paidOn = '';
  protected method: PaymentMethod = 'OTHER';
  protected note = '';

  protected startCorrection(payment: PaymentResponse) {
    this.editing.set(payment);
    this.writeError.set(null);
    this.payerId = payment.payer.id;
    this.payeeId = payment.payee.id;
    this.amount = this.display(payment.amountMinor, payment.currency ?? 'USD');
    this.paidOn = payment.paidOn;
    this.method = payment.method;
    this.note = payment.note ?? '';
    if (!this.people().length) this.loadRoster();
  }

  private loadRoster() {
    this.groupsApi
      .getGroup(this.groupId)
      .pipe(
        takeUntil(this.scopeChanges),
        takeUntil(this.session.changes),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (group) => this.people.set(financialRoster(group)),
        error: () => this.writeError.set(translate('paymentHistory.couldNotLoadThis')),
      });
  }

  protected cancelCorrection() {
    if (this.saving()) return;
    this.editing.set(null);
    this.writeError.set(null);
  }

  protected saveCorrection() {
    const payment = this.editing();
    if (!payment || this.saving()) return;
    const currency = payment.currency ?? 'USD';
    const minor = this.toMinor(this.amount, currency);
    if (minor === null || minor <= 0) {
      this.writeError.set(translate('paymentHistory.enterAnAmountGreater'));
      return;
    }
    if (this.payerId === this.payeeId) {
      this.writeError.set(translate('paymentHistory.aSettlementIsBetween'));
      return;
    }
    this.saving.set(true);
    this.writeError.set(null);
    this.service
      .correctPayment(this.groupId, payment.id, {
        payerUserId: this.payerId,
        payeeUserId: this.payeeId,
        amountMinor: minor,
        paidOn: this.paidOn,
        method: this.method,
        note: this.note.trim() ? this.note.trim() : null,
        expectedVersion: payment.version,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.editing.set(null);
          this.notice.set(translate('paymentHistory.settlementCorrectedTheOriginal'));
          this.dashboard.refresh();
        },
        error: (err) => {
          this.saving.set(false);
          // The form stays open with the user's values; a stale edit is never retried for them.
          this.writeError.set(
            err.status === 409
              ? translate('paymentHistory.thisSettlementChangedReload')
              : translate('paymentHistory.couldNotSaveThis'),
          );
        },
      });
  }

  // --- undo and restore ---------------------------------------------------
  protected readonly confirming = signal<PaymentResponse | null>(null);

  protected requestUndo(payment: PaymentResponse) {
    this.confirming.set(payment);
    this.writeError.set(null);
  }

  protected cancelUndo() {
    this.confirming.set(null);
  }

  protected undo() {
    const payment = this.confirming();
    if (!payment || this.saving()) return;
    this.saving.set(true);
    this.service
      .deletePayment(this.groupId, payment.id, payment.version)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.confirming.set(null);
          this.notice.set(translate('paymentHistory.settlementUndoneYouCan'));
          this.dashboard.refresh();
        },
        error: (err) => {
          this.saving.set(false);
          this.confirming.set(null);
          this.writeError.set(
            err.status === 409
              ? translate('paymentHistory.thisSettlementChangedReload2')
              : translate('paymentHistory.couldNotUndoThis'),
          );
        },
      });
  }

  protected restore(entry: DeletedPayment) {
    if (this.saving()) return;
    this.saving.set(true);
    this.writeError.set(null);
    this.service
      .restorePayment(this.groupId, entry.payment.id, entry.payment.version)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.notice.set(translate('paymentHistory.settlementRestored'));
          this.dashboard.refresh();
        },
        error: (err) => {
          this.saving.set(false);
          this.writeError.set(
            err.status === 409
              ? translate('paymentHistory.thisSettlementChangedReload3')
              : translate('paymentHistory.couldNotRestoreThis'),
          );
        },
      });
  }

  // --- revisions ----------------------------------------------------------
  protected readonly revisionsFor = signal<string | null>(null);
  protected readonly revisions = signal<LedgerRevision<PaymentResponse>[]>([]);
  protected readonly revisionsError = signal<string | null>(null);

  protected toggleRevisions(payment: PaymentResponse) {
    if (this.revisionsFor() === payment.id) {
      this.revisionsFor.set(null);
      this.revisions.set([]);
      return;
    }
    this.revisionsFor.set(payment.id);
    this.revisions.set([]);
    this.revisionsError.set(null);
    this.service
      .history(this.groupId, payment.id)
      .pipe(
        takeUntil(this.scopeChanges),
        takeUntil(this.session.changes),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (revisions) => this.revisions.set(revisions),
        error: () => this.revisionsError.set(translate('paymentHistory.couldNotLoadThis2')),
      });
  }

  // --- money helpers ------------------------------------------------------
  protected display(minor: number, currency: Currency): string {
    const exponent = CURRENCY_EXPONENT[currency];
    if (!exponent) return String(minor);
    const factor = 10 ** exponent;
    return (minor / factor).toFixed(exponent);
  }

  /** Exact integer minor units; a non-finite or fractional-unit entry is rejected, never rounded. */
  protected toMinor(value: string, currency: Currency): number | null {
    const text = value.trim();
    if (!text) return null;
    const parsed = Number(text);
    if (!Number.isFinite(parsed)) return null;
    const minor = Math.round(parsed * 10 ** CURRENCY_EXPONENT[currency]);
    return Number.isSafeInteger(minor) ? minor : null;
  }
}
