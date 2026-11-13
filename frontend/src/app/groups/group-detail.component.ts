import { RecurringService } from '../recurring/recurring.service';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subject, Subscription, forkJoin, takeUntil } from 'rxjs';
import { SessionService } from '../core/auth/session.service';
import { GroupArtComponent } from '../shared/group-art.component';
import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { ParticipantSummary } from '../groups/participant.models';
import { BalanceService } from '../balances/balance.service';
import { PersonBalance, ExpenseCategory } from '../dashboard/dashboard.models';
import { DashboardService } from '../dashboard/dashboard.service';
import { Currency, CURRENCY_EXPONENT } from '../shared/currency';
import { formatMoney } from '../shared/money.component';
import { ExpenseService } from '../expenses/expense.service';
import {
  ExpenseResponse,
  ExpenseSummary,
  SplitShare,
  ExpenseRevision,
  DeletedExpense,
} from '../expenses/expense.models';
import { ModalService } from '../modals/modal.service';
import { avatarTint, groupGlyphTint } from '../core/avatar';
import { GroupService } from './group.service';
import { PaymentService } from '../payments/payment.service';
import { RefundResponse } from '../payments/payment.models';
import { financialRoster, ownedIdentities, viewerIdentity, GroupResponse } from './group.models';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { intlLocale, translate } from '../core/i18n/i18n.service';

const CATEGORY_GLYPH: Record<ExpenseCategory, string> = {
  FOOD_DRINK: '☕',
  GROCERIES: '🧺',
  RENT_HOME: '⌂',
  UTILITIES: '⚡',
  TRAVEL: '✈',
  TRANSPORT: '🚕',
  FUN: '✦',
  OTHER: '◆',
};

interface MonthGroup {
  label: string;
  items: ExpenseSummary[];
}

@Component({
  selector: 'app-group-detail',
  imports: [TranslatePipe, RouterLink, GroupArtComponent],
  templateUrl: './group-detail.component.html',
  styleUrl: './group-detail.component.scss',
})
export class GroupDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly session = inject(SessionService);
  private readonly scopeChanges = new Subject<void>();
  private loadRequest?: Subscription;

  /** End UI callbacks when their route, account or component no longer owns them. */
  private scoped<T>(request: Observable<T>): Observable<T> {
    return request.pipe(
      takeUntil(this.scopeChanges),
      takeUntil(this.session.changes),
      takeUntilDestroyed(this.destroyRef),
    );
  }
  private readonly groups = inject(GroupService);
  private readonly balances = inject(BalanceService);
  private readonly recurring = inject(RecurringService);
  private readonly expensesApi = inject(ExpenseService);
  private readonly auth = inject(AuthService);
  private readonly dashboard = inject(DashboardService);
  protected readonly modal = inject(ModalService);

  protected readonly display = (amount: number, currency: Currency = 'USD') =>
    formatMoney(amount, false, currency);

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly group = signal<GroupResponse | null>(null);
  protected readonly myBalances = signal<PersonBalance[]>([]);
  private readonly ledgerCurrencies = signal<Currency[]>([]);
  protected readonly expenses = signal<ExpenseSummary[]>([]);

  /** The expense whose detail row is expanded, plus its fetched full breakdown. */
  protected readonly expandedId = signal<string | null>(null);
  protected readonly detail = signal<ExpenseResponse | null>(null);
  protected readonly detailLoading = signal(false);
  protected readonly detailError = signal<string | null>(null);
  protected readonly deleteError = signal<{ expenseId: string; message: string } | null>(null);
  protected readonly deletingId = signal<string | null>(null);
  /** Fetched details, kept so re-expanding a row is instant (no spinner flash). */
  private detailRequest?: Subscription;
  private readonly detailCache = new Map<string, ExpenseResponse>();

  protected readonly historyId = signal<string | null>(null);
  protected readonly history = signal<ExpenseRevision[]>([]);
  protected readonly historyLoading = signal(false);
  protected readonly historyError = signal<string | null>(null);
  private historyRequest?: Subscription;

  protected readonly recoveryOpen = signal(false);
  protected readonly deletedExpenses = signal<DeletedExpense[]>([]);
  protected readonly recoveryLoading = signal(false);
  protected readonly recoveryError = signal<string | null>(null);
  protected readonly restoreError = signal<{ expenseId: string; message: string } | null>(null);
  protected readonly recoveryNeedsReview = signal(false);
  protected readonly recoveryNotice = signal<string | null>(null);
  protected readonly restoringId = signal<string | null>(null);
  protected readonly writePending = computed(() => !!this.deletingId() || !!this.restoringId());

  /** Archived groups are read-only: history stays, new expenses and settlements are refused. */
  protected readonly archived = computed(() => !!this.group()?.archivedAt);
  private recoveryRequest?: Subscription;

  protected groupId = ''; // read by the template for the settings link

  /** My net in this group = sum of the pairwise nets (positive = I'm owed overall). */
  protected readonly myTotals = computed(() => {
    const amounts = new Map<Currency, number>(
      this.ledgerCurrencies().map((currency) => [currency, 0]),
    );
    for (const balance of this.myBalances()) {
      const currency = balance.currency ?? 'USD';
      amounts.set(currency, (amounts.get(currency) ?? 0) + balance.netMinor);
    }
    if (!amounts.size) amounts.set('USD', 0);
    return [...amounts]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([currency, netMinor]) => ({
        currency,
        netMinor,
        hasOpenBalances: this.myBalances().some(
          (balance) => (balance.currency ?? 'USD') === currency && balance.netMinor !== 0,
        ),
      }));
  });

  /** Expenses grouped by "MONTH YEAR", newest month first (list already sorted desc). */
  protected readonly months = computed<MonthGroup[]>(() => {
    const out: MonthGroup[] = [];
    for (const e of this.expenses()) {
      const label = this.monthLabel(e.spentOn);
      const last = out[out.length - 1];
      if (last && last.label === label) {
        last.items.push(e);
      } else {
        out.push({ label, items: [e] });
      }
    }
    return out;
  });

  constructor() {
    this.session.changes.pipe(takeUntilDestroyed()).subscribe(() => {
      this.clearHistory();
      this.clearRecovery();
      this.recoveryNotice.set(null);
      this.group.set(null);
      this.myBalances.set([]);
      this.ledgerCurrencies.set([]);
      this.expenses.set([]);
      this.detailCache.clear();
      this.expandedId.set(null);
      this.detail.set(null);
      this.detailLoading.set(false);
      this.detailError.set(null);
      this.deleteError.set(null);
      this.deletingId.set(null);
      this.error.set(null);
      this.loading.set(false);
    });
    // Group reads do not depend on the overview request succeeding.
    this.dashboard.refreshes.pipe(takeUntilDestroyed()).subscribe((force) => {
      if (this.groupId) this.reload(force);
    });
  }

  ngOnInit(): void {
    // Subscribe (not snapshot): Angular reuses this component when navigating between
    // /groups/:id, so ngOnInit runs once — the snapshot would freeze on the first group.
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const id = params.get('id') ?? '';
      if (id === this.groupId) {
        return;
      }
      this.scopeChanges.next();
      this.clearHistory();
      this.clearRecovery();
      this.recoveryNotice.set(null);
      this.group.set(null);
      this.myBalances.set([]);
      this.ledgerCurrencies.set([]);
      this.expenses.set([]);
      this.detailError.set(null);
      this.deleteError.set(null);
      this.deletingId.set(null);
      this.groupId = id;
      this.loading.set(true);
      this.error.set(null);
      this.expandedId.set(null);
      this.detail.set(null);
      this.reload();
    });
  }

  private reload(force = true): void {
    if (this.writePending()) return;
    if (!force && this.loadRequest && !this.loadRequest.closed) return;
    this.loadRequest?.unsubscribe();
    if (this.recoveryOpen() && (force || !this.recoveryRequest || this.recoveryRequest.closed)) {
      this.loadDeleted(true);
    }
    const historyId = this.historyId();
    if (!force && historyId && !this.historyLoading() && !this.historyError()) {
      this.viewHistory(historyId, true);
    }
    if (force) {
      this.clearHistory();
      this.detailRequest?.unsubscribe();
      this.detailCache.clear();
      this.expandedId.set(null);
      this.detail.set(null);
      this.detailLoading.set(false);
    }
    this.loading.set(true);
    this.error.set(null);
    this.loadRequest = this.scoped(
      forkJoin({
        group: this.groups.getGroup(this.groupId),
        balances: this.balances.getMyBalances(this.groupId),
        ledger: this.balances.getBalances(this.groupId),
        expenses: this.expensesApi.listExpenses(this.groupId),
      }),
    ).subscribe({
      next: (res) => {
        this.group.set(res.group);
        this.myBalances.set(res.balances);
        this.ledgerCurrencies.set(res.ledger.balances.map((balance) => balance.currency ?? 'USD'));
        this.expenses.set(res.expenses);
        this.loading.set(false);
        this.detailCache.clear();
        const selected = res.expenses.find((e) => e.id === this.expandedId());
        if (selected && !this.detailError()) {
          this.expandedId.set(null);
          this.toggleExpand(selected, true);
        } else if (!selected) {
          this.expandedId.set(null);
          this.detail.set(null);
          this.detailLoading.set(false);
        }
      },
      error: (err) => {
        if (err?.status === 403 || err?.status === 404) {
          this.detailRequest?.unsubscribe();
          this.clearHistory();
          this.clearRecovery();
          this.recoveryNotice.set(null);
          this.group.set(null);
          this.myBalances.set([]);
          this.ledgerCurrencies.set([]);
          this.expenses.set([]);
          this.detailCache.clear();
          this.expandedId.set(null);
          this.detail.set(null);
        }
        this.error.set(
          err?.status === 403
            ? translate('groupDetail.youAreNotA')
            : translate('groupDetail.couldNotLoadThis'),
        );
        this.loading.set(false);
      },
    });
  }

  retry(): void {
    this.reload(false);
  }

  addExpense(): void {
    if (this.writePending() || this.archived()) return;
    this.modal.openExpense(this.groupId);
  }

  settleUp(): void {
    if (this.writePending() || this.archived()) return;
    this.modal.openSettle(undefined, this.groupId);
  }

  /** Expand a row to show its split, or collapse it if it's already open. */
  // --- refunds: money the seller returned out of one expense ---------------
  private readonly payments = inject(PaymentService);
  private refundRequest?: Subscription;
  protected readonly refunds = signal<RefundResponse[]>([]);
  protected readonly refundFor = signal<string | null>(null);
  protected readonly refundSaving = signal(false);
  protected readonly refundError = signal<string | null>(null);
  protected refundAmount = '';
  protected refundedOn = '';
  protected refundNote = '';

  startRefund(expense: ExpenseResponse): void {
    if (this.writePending()) return;
    this.refundFor.set(expense.id);
    this.refundError.set(null);
    this.refundAmount = '';
    this.refundedOn = expense.spentOn;
    this.refundNote = '';
    this.loadRefunds(expense);
  }

  private loadRefunds(expense: ExpenseResponse): void {
    this.refundRequest?.unsubscribe();
    this.refunds.set([]);
    this.refundRequest = this.payments
      .listRefunds(this.groupId, expense.id)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (refunds) => this.refunds.set(refunds),
        error: () => this.refundError.set(translate('groupDetail.couldNotLoadThis2')),
      });
  }

  cancelRefund(): void {
    if (this.refundSaving()) return;
    this.refundFor.set(null);
    this.refundError.set(null);
  }

  /** The remainder guard runs here too, so an impossible refund never leaves the browser. */
  saveRefund(expense: ExpenseResponse): void {
    if (this.refundSaving()) return;
    const currency = expense.currency ?? 'USD';
    const minor = this.toMinorUnits(this.refundAmount, currency);
    const remaining =
      expense.amountMinor - this.refunds().reduce((sum, r) => sum + r.amountMinor, 0);
    if (minor === null || minor <= 0) {
      this.refundError.set(translate('groupDetail.enterARefundAmount'));
      return;
    }
    if (minor > remaining) {
      this.refundError.set(
        translate('groupDetail.onlyCurrencyIsLeft', {
          currency: this.display(remaining, currency),
        }),
      );
      return;
    }
    if (!this.refundedOn) {
      this.refundError.set(translate('groupDetail.chooseTheDateOf'));
      return;
    }
    this.refundSaving.set(true);
    this.refundError.set(null);
    this.payments
      .recordRefund(this.groupId, expense.id, {
        amountMinor: minor,
        currency,
        refundedOn: this.refundedOn,
        note: this.refundNote.trim() ? this.refundNote.trim() : null,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (refund) => {
          this.refundSaving.set(false);
          this.refundFor.set(null);
          this.refunds.update((existing) => [refund, ...existing]);
          this.dashboard.refresh();
        },
        error: (err) => {
          this.refundSaving.set(false);
          this.refundError.set(
            err.status === 400
              ? translate('groupDetail.thisRefundIsLarger')
              : translate('groupDetail.couldNotRecordThis'),
          );
        },
      });
  }

  private toMinorUnits(value: string, currency: Currency): number | null {
    const text = value.trim();
    if (!text) return null;
    const parsed = Number(text);
    if (!Number.isFinite(parsed)) return null;
    const minor = Math.round(parsed * 10 ** CURRENCY_EXPONENT[currency]);
    return Number.isSafeInteger(minor) ? minor : null;
  }

  toggleExpand(e: ExpenseSummary, preserveHistory = false): void {
    if (!preserveHistory) this.clearHistory();
    this.detailRequest?.unsubscribe();
    this.detailError.set(null);
    if (this.expandedId() === e.id) {
      this.expandedId.set(null);
      this.detail.set(null);
      return;
    }
    this.expandedId.set(e.id);
    const cached = this.detailCache.get(e.id);
    if (cached) {
      this.detail.set(cached);
      this.detailLoading.set(false);
      return;
    }
    this.detail.set(null);
    this.detailLoading.set(true);
    this.detailRequest = this.scoped(this.expensesApi.getExpense(this.groupId, e.id)).subscribe({
      next: (d) => {
        this.detailCache.set(e.id, d);
        if (this.expandedId() === e.id) {
          this.detail.set(d);
        }
        this.detailLoading.set(false);
      },
      error: () => {
        this.detailLoading.set(false);
        this.detailError.set(translate('groupDetail.couldNotLoadThis3'));
      },
    });
  }

  retryDetail(): void {
    const expense = this.expenses().find((e) => e.id === this.expandedId());
    if (!expense || this.detailLoading()) return;
    this.expandedId.set(null);
    this.toggleExpand(expense);
  }

  editExpense(e: ExpenseSummary): void {
    if (this.writePending()) return;
    this.modal.openExpense(this.groupId, e.id);
  }

  deleteExpense(e: ExpenseSummary): void {
    if (this.writePending()) {
      return;
    }
    if (!Number.isSafeInteger(e.version) || e.version! < 0) {
      this.deleteError.set({
        expenseId: e.id,
        message: translate('groupDetail.reloadOrUpgradeThe'),
      });
      return;
    }
    if (
      !confirm(translate('groupDetail.deleteDescriptionYouCan', { description: e.description }))
    ) {
      return;
    }
    this.deleteError.set(null);
    this.deletingId.set(e.id);
    this.scoped(this.expensesApi.deleteExpense(this.groupId, e.id, e.version)).subscribe({
      next: () => {
        this.expandedId.set(null);
        this.detail.set(null);
        this.deletingId.set(null);
        this.dashboard.refresh(); // effect() re-pulls this group's expenses + balances
      },
      error: (err) => {
        this.deletingId.set(null);
        this.deleteError.set({
          expenseId: e.id,
          message: err?.error?.message ?? translate('groupDetail.couldNotDeleteThis'),
        });
      },
    });
  }

  private clearRecovery(): void {
    this.recoveryRequest?.unsubscribe();
    this.recoveryOpen.set(false);
    this.deletedExpenses.set([]);
    this.recoveryLoading.set(false);
    this.recoveryError.set(null);
    this.restoreError.set(null);
    this.recoveryNeedsReview.set(false);
    this.restoringId.set(null);
  }

  toggleDeleted(): void {
    if (this.recoveryOpen()) {
      this.clearRecovery();
    } else {
      this.recoveryOpen.set(true);
      this.loadDeleted();
    }
  }

  reviewDeleted(): void {
    if (this.writePending()) return;
    this.recoveryNotice.set(null);
    this.restoreError.set(null);
    this.recoveryNeedsReview.set(false);
    this.dashboard.refresh();
  }

  loadDeleted(preserveFeedback = false): void {
    if (this.writePending()) return;
    this.recoveryRequest?.unsubscribe();
    this.recoveryLoading.set(true);
    this.recoveryError.set(null);
    this.recoveryRequest = this.scoped(this.expensesApi.listDeleted(this.groupId)).subscribe({
      next: (expenses) => {
        this.deletedExpenses.set(expenses);
        this.recoveryLoading.set(false);
        if (!preserveFeedback) {
          this.restoreError.set(null);
          this.recoveryNeedsReview.set(false);
        }
      },
      error: (err) => {
        this.recoveryLoading.set(false);
        if (err?.status === 403 || err?.status === 404) {
          this.deletedExpenses.set([]);
          this.clearHistory();
        }
        this.recoveryError.set(err?.error?.message ?? translate('groupDetail.couldNotLoadDeleted'));
      },
    });
  }

  restoreExpense(expense: ExpenseResponse): void {
    if (
      this.writePending() ||
      this.loading() ||
      this.error() ||
      this.recoveryLoading() ||
      this.recoveryError() ||
      this.recoveryNeedsReview()
    )
      return;
    if (!Number.isSafeInteger(expense.version) || expense.version! < 0) {
      this.restoreError.set({
        expenseId: expense.id,
        message: translate('groupDetail.reloadOrUpgradeThe2'),
      });
      this.recoveryNeedsReview.set(true);
      return;
    }
    this.restoreError.set(null);
    this.recoveryNotice.set(null);
    this.restoringId.set(expense.id);
    this.scoped(
      this.expensesApi.restoreExpense(this.groupId, expense.id, expense.version!),
    ).subscribe({
      next: () => {
        this.restoringId.set(null);
        this.deletedExpenses.update((expenses) =>
          expenses.filter((entry) => entry.expense.id !== expense.id),
        );
        this.recoveryNotice.set(translate('groupDetail.expenseRestoredBalancesHave'));
        this.dashboard.refresh();
      },
      error: (err) => {
        this.restoringId.set(null);
        this.recoveryNeedsReview.set(true);
        this.restoreError.set({
          expenseId: expense.id,
          message:
            !err?.status || err.status >= 500
              ? translate('groupDetail.couldNotConfirmRestoration')
              : (err?.error?.message ?? translate('groupDetail.couldNotRestoreThis')),
        });
      },
    });
  }

  splitLabel(type: ExpenseResponse['splitType']): string {
    return {
      EQUAL: translate('groupDetail.equal'),
      UNEQUAL: translate('personalExpenses.exactAmounts'),
      PERCENTAGE: translate('groupDetail.percentages'),
      WEIGHTS: translate('newExpenseModal.weights'),
      ADJUSTMENTS: translate('newExpenseModal.adjustments'),
    }[type];
  }

  splitInput(snapshot: ExpenseResponse, value: number): string {
    if (snapshot.splitType === 'PERCENTAGE') return `${value / 100}%`;
    if (snapshot.splitType === 'WEIGHTS') return String(value);
    return this.display(value, snapshot.currency);
  }

  clearHistory(): void {
    this.historyRequest?.unsubscribe();
    this.historyId.set(null);
    this.history.set([]);
    this.historyLoading.set(false);
    this.historyError.set(null);
  }

  viewHistory(expenseId: string, refresh = false): void {
    if (!refresh) this.clearHistory();
    this.historyId.set(expenseId);
    this.historyLoading.set(true);
    this.historyRequest = this.scoped(
      this.expensesApi.getHistory(this.groupId, expenseId),
    ).subscribe({
      next: (history) => {
        this.history.set(history);
        this.historyLoading.set(false);
      },
      error: (err) => {
        this.historyLoading.set(false);
        this.historyError.set(err?.error?.message ?? translate('groupDetail.couldNotLoadExpense'));
      },
    });
  }

  revisionLabel(action: ExpenseRevision['action']): string {
    return {
      IMPORTED: translate('groupDetail.importedBaseline'),
      CREATED: translate('groupDetail.created'),
      REPLACED: translate('groupDetail.replaced'),
      DELETED: translate('groupDetail.deleted'),
      RESTORED: translate('groupDetail.restored'),
    }[action];
  }

  recordedDate(iso: string): string {
    return new Date(iso).toLocaleString(intlLocale(), { dateStyle: 'medium', timeStyle: 'short' });
  }

  inputName(snapshot: ExpenseResponse, userId: string): string {
    return (
      snapshot.participants.find((participant) => participant.user.id === userId)?.user
        .displayName ?? translate('groupDetail.participant')
    );
  }

  /** Detail-row label: "You owe" / "Priya owes" for each participant's share. */
  partLabel(p: SplitShare): string {
    return this.isOwned(p.user.id)
      ? translate('dashboard.youOwe')
      : translate('groupDetail.v1Owes', { v1: p.user.displayName.split(/\s+/)[0] });
  }

  meId(): string {
    const group = this.group();
    return group ? viewerIdentity(group, this.auth.user()?.id ?? '') : '';
  }

  private isOwned(participantId: string): boolean {
    const group = this.group();
    return !!group && ownedIdentities(group, this.auth.user()?.id ?? '').includes(participantId);
  }

  sameSnapshotOwner(snapshot: ExpenseResponse, participant: ParticipantSummary): boolean {
    return (
      !!participant.canonicalParticipantId &&
      snapshot.participants.some(
        (row) =>
          row.user.id !== participant.id &&
          row.user.canonicalParticipantId === participant.canonicalParticipantId,
      )
    );
  }

  firstName(u: ParticipantSummary): string {
    return this.isOwned(u.id) ? translate('dashboard.you') : u.displayName.split(/\s+/)[0];
  }

  memberNames(g: GroupResponse): string {
    return financialRoster(g)
      .map((m) => this.firstName(m))
      .join(', ');
  }

  typeLabel(g: GroupResponse): string {
    const t = (g as unknown as { type?: string }).type ?? 'OTHER';
    return t.charAt(0) + t.slice(1).toLowerCase();
  }

  glyphTint(name: string): { background: string; color: string } {
    return groupGlyphTint(name);
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

  protected readonly reminderNotice = signal<string | null>(null);
  protected readonly reminderError = signal<string | null>(null);

  /** Nudges someone who owes me; the server allows one per person and currency per day. */
  remind(b: PersonBalance): void {
    this.reminderNotice.set(null);
    this.reminderError.set(null);
    const name = b.user.displayName.split(/\s+/)[0];
    this.recurring
      .remind(this.groupId, b.user.accountId ?? b.user.id, b.currency ?? 'USD')
      .pipe(
        takeUntil(this.scopeChanges),
        takeUntil(this.session.changes),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => this.reminderNotice.set(translate('groupDetail.reminderSentToName', { name })),
        error: (err) =>
          this.reminderError.set(err?.error?.message ?? translate('groupDetail.couldNotSendThe')),
      });
  }

  pillLabel(b: PersonBalance): string {
    const name = b.user.displayName.split(/\s+/)[0];
    return b.netMinor > 0
      ? translate('groupDetail.nameOwesYou', { name })
      : translate('groupDetail.youOweName', { name });
  }

  categoryGlyph(c: ExpenseCategory): string {
    return CATEGORY_GLYPH[c] ?? '◆';
  }

  paidSub(e: ExpenseSummary): string {
    const who = this.isOwned(e.paidBy.id)
      ? translate('dashboard.you')
      : e.paidBy.displayName.split(/\s+/)[0];
    const others = (e.payerCount ?? 1) - 1;
    const names = others > 0 ? translate('groupDetail.whoAndOthers', { who, count: others }) : who;
    return translate('groupDetail.namesPaidCurrency', {
      names,
      currency: this.display(e.amountMinor, e.currency),
    });
  }

  deltaLabel(e: ExpenseSummary): string {
    if (e.viewerDeltaMinor > 0) return translate('groupDetail.youLent');
    if (e.viewerDeltaMinor < 0) return translate('groupDetail.youBorrowed');
    return translate('groupDetail.notInvolved');
  }

  private monthLabel(iso: string): string {
    return new Date(iso + 'T00:00:00')
      .toLocaleDateString(intlLocale(), { month: 'long', year: 'numeric' })
      .toUpperCase();
  }

  fullDate(iso: string): string {
    return new Date(iso + 'T00:00:00').toLocaleDateString(intlLocale(), {
      month: 'long',
      day: 'numeric',
      year: 'numeric',
    });
  }

  dayNum(iso: string): string {
    return String(new Date(iso + 'T00:00:00').getDate());
  }

  dayMon(iso: string): string {
    return new Date(iso + 'T00:00:00')
      .toLocaleDateString(intlLocale(), { month: 'short' })
      .toUpperCase();
  }
}
