import { Component, DestroyRef, effect, inject, input, signal, computed } from '@angular/core';
import { Subscription, takeUntil } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  Currency,
  CURRENCIES,
  MAX_TRANSACTION_MINOR,
  amountToMinor,
  minorToDisplay,
} from '../shared/currency';
import { MoneyComponent } from '../shared/money.component';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';
import { DashboardService } from '../dashboard/dashboard.service';
import {
  PendingWrite,
  PendingWriteError,
  PendingWritesService,
  UNCERTAIN_WRITE,
} from '../core/pending-writes.service';
import { CreateExpenseRequest, DeletedExpense, ExpenseSummary } from '../expenses/expense.models';
import { FriendService } from './friend.service';

/**
 * Non-group expenses with one saved friend (G02.2). Every entry belongs to the pair, never to a
 * group, so adding, editing and deleting needs only the friend in the route.
 */
@Component({
  selector: 'app-personal-expenses',
  imports: [MoneyComponent],
  templateUrl: './personal-expenses.component.html',
  styleUrl: './personal-expenses.component.scss',
})
export class PersonalExpensesComponent {
  readonly friendId = input.required<string>();
  readonly friendName = input.required<string>();
  private readonly service = inject(FriendService);
  private readonly session = inject(SessionService);
  private readonly auth = inject(AuthService);
  private readonly dashboard = inject(DashboardService);
  private readonly pendingWrites = inject(PendingWritesService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly sessionVersion = this.session.version;
  private request?: Subscription;
  private detailRequest?: Subscription;

  protected readonly currencies = CURRENCIES;
  protected readonly expenses = signal<ExpenseSummary[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly deleting = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly needsReview = signal(false);
  protected readonly draftBlocked = signal(false);
  protected readonly retryNotice = signal<string | null>(null);
  protected readonly formOpen = signal(false);
  protected readonly convertedFrom = signal<string | null>(null);
  protected readonly deletedOpen = signal(false);
  protected readonly deletedEntries = signal<DeletedExpense[]>([]);
  protected readonly deletedLoading = signal(false);
  protected readonly deletedError = signal<string | null>(null);
  protected readonly restoring = signal<string | null>(null);
  private deletedRequest?: Subscription;
  protected editing: string | null = null;
  private expectedVersion: number | undefined;
  protected restored: PendingWrite<CreateExpenseRequest> | null = null;

  protected readonly description = signal('');
  protected readonly amount = signal('');
  protected readonly date = signal(this.today());
  protected readonly currency = signal<Currency>('USD');
  protected readonly paidByMe = signal(true);
  protected readonly split = signal<'EQUAL' | 'UNEQUAL'>('EQUAL');
  protected readonly mine = signal('');
  protected readonly theirs = signal('');
  protected readonly amountMinor = computed(() =>
    amountToMinor(this.amount() || '0', this.currency(), false),
  );
  protected readonly shareTotal = computed(() =>
    this.shares().reduce((sum, value) => sum + value, 0),
  );
  private shares = computed(() => [
    amountToMinor(this.mine() || '0', this.currency(), false),
    amountToMinor(this.theirs() || '0', this.currency(), false),
  ]);

  constructor() {
    effect(() => {
      const id = this.friendId();
      if (this.session.version !== this.sessionVersion) return;
      this.reset();
      this.load(id);
      this.restoreDraft(id);
    });
    this.dashboard.refreshes.pipe(takeUntilDestroyed()).subscribe(() => {
      this.load();
      if (this.deletedOpen() && !this.restoring()) this.loadDeleted();
    });
    this.session.changes.pipe(takeUntilDestroyed()).subscribe(() => {
      this.request?.unsubscribe();
      this.detailRequest?.unsubscribe();
      this.deletedRequest?.unsubscribe();
      this.deletedEntries.set([]);
      this.deletedOpen.set(false);
      this.restoring.set(null);
      this.expenses.set([]);
      this.loading.set(false);
      this.formOpen.set(false);
      this.saving.set(false);
      this.deleting.set(null);
      this.needsReview.set(false);
      this.retryNotice.set(null);
      this.error.set('Session changed. Reopen this friend for the current account.');
    });
  }

  private me(): string {
    return this.auth.user()?.id ?? '';
  }

  protected paidByMeId(): string {
    return this.me();
  }

  private scope(): string {
    return `friend:${this.friendId()}`;
  }

  private today(): string {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  }

  protected display(minor: number): string {
    return minorToDisplay(minor, this.currency());
  }

  private reset(): void {
    this.request?.unsubscribe();
    this.detailRequest?.unsubscribe();
    this.deletedRequest?.unsubscribe();
    this.deletedEntries.set([]);
    this.deletedOpen.set(false);
    this.deletedError.set(null);
    this.restoring.set(null);
    this.expenses.set([]);
    this.loading.set(false);
    this.error.set(null);
    this.notice.set(null);
    this.needsReview.set(false);
    this.deleting.set(null);
    this.saving.set(false);
    this.resetForm();
  }

  private resetForm(): void {
    this.formOpen.set(false);
    this.convertedFrom.set(null);
    this.editing = null;
    this.expectedVersion = undefined;
    this.description.set('');
    this.amount.set('');
    this.date.set(this.today());
    this.currency.set('USD');
    this.paidByMe.set(true);
    this.split.set('EQUAL');
    this.mine.set('');
    this.theirs.set('');
    this.draftBlocked.set(false);
  }

  protected load(id = this.friendId()): void {
    if (this.session.version !== this.sessionVersion) return;
    this.request?.unsubscribe();
    this.loading.set(true);
    this.error.set(null);
    this.request = this.service
      .personalExpenses(id)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          this.expenses.set(rows);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.expenses.set([]);
          this.error.set(
            'Could not load personal expenses, so no total is shown. Try again to review them.',
          );
        },
      });
  }

  protected startAdd(): void {
    if (this.needsReview() || this.saving()) return;
    this.resetForm();
    this.formOpen.set(true);
  }

  protected cancelForm(): void {
    if (this.saving()) return;
    this.resetForm();
    this.retryNotice.set(null);
  }

  protected edit(id: string): void {
    if (this.saving() || this.deleting() || this.needsReview()) return;
    this.resetForm();
    this.formOpen.set(true);
    this.editing = id;
    this.detailRequest?.unsubscribe();
    this.detailRequest = this.service
      .personalExpense(this.friendId(), id)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (e) => {
          if (this.editing !== id) return;
          this.description.set(e.description);
          this.currency.set(e.currency ?? 'USD');
          this.amount.set(minorToDisplay(e.amountMinor, this.currency()));
          this.date.set(e.spentOn);
          this.paidByMe.set(e.paidBy.id === this.me());
          this.expectedVersion = e.version;
          // Only equal and exact are editable here; every other type opens as its stored exact
          // shares so saving never silently resets what was agreed.
          this.split.set(e.splitType === 'EQUAL' ? 'EQUAL' : 'UNEQUAL');
          this.convertedFrom.set(
            e.splitType === 'EQUAL' || e.splitType === 'UNEQUAL'
              ? null
              : { PERCENTAGE: 'percentages', WEIGHTS: 'weights', ADJUSTMENTS: 'adjustments' }[
                  e.splitType
                ],
          );
          this.applyShares(e.splitType === 'UNEQUAL' ? e.splits : null, e.participants);
        },
        error: () => {
          if (this.editing !== id) return;
          this.resetForm();
          this.error.set('Could not load this personal expense. Try again to review it.');
        },
      });
  }

  /** Prefill from recorded intent; older responses carry only final shares. */
  private applyShares(
    splits: { userId: string; value: number }[] | null | undefined,
    participants: { user: { id: string }; shareMinor: number }[],
  ): void {
    const mine = splits
      ? splits.find((s) => s.userId === this.me())?.value
      : participants.find((p) => p.user.id === this.me())?.shareMinor;
    const theirs = splits
      ? splits.find((s) => s.userId === this.friendId())?.value
      : participants.find((p) => p.user.id === this.friendId())?.shareMinor;
    this.mine.set(mine === undefined ? '' : this.display(mine));
    this.theirs.set(theirs === undefined ? '' : this.display(theirs));
  }

  protected canSave(): boolean {
    const amount = this.amountMinor();
    if (
      this.saving() ||
      this.draftBlocked() ||
      this.needsReview() ||
      !this.description().trim() ||
      !Number.isSafeInteger(amount) ||
      amount <= 0 ||
      amount > MAX_TRANSACTION_MINOR
    )
      return false;
    if (this.split() === 'EQUAL') return true;
    const total = this.shareTotal();
    return (
      Number.isSafeInteger(total) &&
      total === amount &&
      this.shares().every((share) => Number.isSafeInteger(share) && share >= 0)
    );
  }

  protected save(): void {
    if (!this.canSave() || this.session.version !== this.sessionVersion) return;
    const base = {
      description: this.description().trim(),
      amountMinor: this.amountMinor(),
      currency: this.currency(),
      paidByUserId: this.paidByMe() ? this.me() : this.friendId(),
      category: 'OTHER' as const,
      spentOn: this.date(),
    };
    const request: CreateExpenseRequest =
      this.split() === 'EQUAL'
        ? { ...base, participantUserIds: [this.me(), this.friendId()] }
        : {
            ...base,
            splitType: 'UNEQUAL',
            splits: [
              { userId: this.me(), value: this.shares()[0] },
              { userId: this.friendId(), value: this.shares()[1] },
            ],
          };
    this.dispatch(this.editing ? { ...request, expectedVersion: this.expectedVersion } : request);
  }

  /** Never auto-retry: an uncertain entry keeps its keyed identity until the reader reviews it. */
  private dispatch(request: CreateExpenseRequest): void {
    let pending: PendingWrite<CreateExpenseRequest> | null = null;
    if (!this.editing) {
      try {
        pending = this.pendingWrites.prepare('expense', this.scope(), request);
        this.restored = pending;
      } catch (err) {
        if (err instanceof PendingWriteError) {
          this.draftBlocked.set(true);
          this.retryNotice.set(err.message);
        }
        return;
      }
    }
    this.saving.set(true);
    this.notice.set(null);
    this.error.set(null);
    const save$ = this.editing
      ? this.service.updatePersonalExpense(this.friendId(), this.editing, request)
      : this.service.createPersonalExpense(this.friendId(), {
          ...request,
          operationId: pending!.operationId,
        });
    save$.pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        if (pending) this.pendingWrites.complete('expense', pending);
        this.saving.set(false);
        this.restored = null;
        this.retryNotice.set(null);
        this.notice.set(this.editing ? 'Personal expense updated.' : 'Personal expense added.');
        this.resetForm();
        this.dashboard.refresh();
      },
      error: (err) => {
        this.saving.set(false);
        this.error.set(err?.error?.message ?? 'Could not save the personal expense.');
        if (!this.editing) {
          this.needsReview.set(true);
          this.retryNotice.set(UNCERTAIN_WRITE);
        }
      },
    });
  }

  protected retryOriginal(): void {
    if (this.saving() || !this.restored) return;
    this.dispatch(this.restored.request);
  }

  protected reviewEntries(): void {
    this.needsReview.set(false);
    this.error.set(null);
    this.load();
  }

  protected startSeparate(): void {
    if (
      this.saving() ||
      !confirm(
        'The previous expense may already have been saved. Check personal expenses first. Start a separate expense anyway?',
      )
    )
      return;
    try {
      this.pendingWrites.abandon('expense');
    } catch {
      this.error.set(
        'Cannot clear the saved retry identity. Enable browser storage and try again.',
      );
      return;
    }
    this.restored = null;
    this.retryNotice.set(null);
    this.needsReview.set(false);
    this.resetForm();
    this.formOpen.set(true);
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

  protected loadDeleted(): void {
    if (this.session.version !== this.sessionVersion) return;
    this.deletedRequest?.unsubscribe();
    this.deletedLoading.set(true);
    this.deletedError.set(null);
    this.deletedRequest = this.service
      .deletedPersonalExpenses(this.friendId())
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          this.deletedEntries.set(rows);
          this.deletedLoading.set(false);
        },
        error: () => {
          this.deletedEntries.set([]);
          this.deletedLoading.set(false);
          this.deletedError.set(
            'Could not load deleted personal expenses. Try again to review them.',
          );
        },
      });
  }

  protected restore(entry: DeletedExpense): void {
    const version = entry.expense.version;
    if (this.restoring() || this.deletedLoading()) return;
    if (version === undefined || !Number.isSafeInteger(version)) {
      this.deletedError.set(
        'Reload deleted expenses before restoring, so it is not applied twice.',
      );
      return;
    }
    this.restoring.set(entry.expense.id);
    this.deletedError.set(null);
    this.service
      .restorePersonalExpense(this.friendId(), entry.expense.id, version)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.restoring.set(null);
          this.deletedEntries.update((rows) =>
            rows.filter((r) => r.expense.id !== entry.expense.id),
          );
          this.notice.set('Personal expense restored.');
          this.dashboard.refresh();
        },
        error: (err) => {
          this.restoring.set(null);
          this.deletedError.set(
            !err?.status || err.status >= 500
              ? 'Could not confirm the restore. Reload deleted expenses and check entries before trying again.'
              : (err?.error?.message ?? 'Could not restore this expense. Reload and review it.'),
          );
        },
      });
  }

  protected remove(row: ExpenseSummary): void {
    if (this.saving() || this.deleting() || this.needsReview()) return;
    if (row.version === undefined || !Number.isSafeInteger(row.version)) {
      this.error.set('Reload this friend before deleting, so the change is not applied twice.');
      return;
    }
    this.deleting.set(row.id);
    this.error.set(null);
    this.service
      .deletePersonalExpense(this.friendId(), row.id, row.version)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.deleting.set(null);
          this.notice.set('Personal expense deleted.');
          this.dashboard.refresh();
        },
        error: (err) => {
          this.deleting.set(null);
          this.error.set(
            err?.error?.message ??
              'Could not delete this personal expense. Reload to review the current entry.',
          );
        },
      });
  }

  private restoreDraft(id: string): void {
    let pending: PendingWrite<CreateExpenseRequest> | null;
    try {
      pending = this.pendingWrites.read<CreateExpenseRequest>('expense');
    } catch (err) {
      this.draftBlocked.set(true);
      this.retryNotice.set(
        err instanceof PendingWriteError
          ? err.message
          : 'Cannot read the saved retry draft. Check entries before starting a separate one.',
      );
      return;
    }
    if (!pending || pending.scope !== `friend:${id}`) return;
    this.restored = pending;
    const request = pending.request;
    this.formOpen.set(true);
    this.description.set(request.description ?? '');
    this.currency.set(request.currency ?? 'USD');
    this.amount.set(minorToDisplay(request.amountMinor ?? 0, this.currency()));
    this.date.set(request.spentOn ?? this.today());
    this.paidByMe.set(request.paidByUserId !== id);
    this.split.set(request.splitType === 'UNEQUAL' ? 'UNEQUAL' : 'EQUAL');
    this.mine.set('');
    this.theirs.set('');
    this.applyShares(request.splits, []);
    this.retryNotice.set(UNCERTAIN_WRITE);
  }
}
