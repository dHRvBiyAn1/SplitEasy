import { Frequency } from '../recurring/recurring.models';
import { RecurringService } from '../recurring/recurring.service';
import { formatMoney } from '../shared/money.component';
import { ProfileService } from '../core/profile.service';
import {
  Currency,
  CURRENCIES,
  CURRENCY_EXPONENT,
  MAX_TRANSACTION_MINOR,
  amountToMinor,
  minorToDisplay,
} from '../shared/currency';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DestroyRef, Component, computed, effect, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { takeUntil } from 'rxjs';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';
import { avatarTint } from '../core/avatar';
import { ParticipantSummary } from '../groups/participant.models';
import { financialRoster, viewerIdentity, ownedIdentities } from '../groups/group.models';
import { DashboardService } from '../dashboard/dashboard.service';
import { ExpenseCategory } from '../dashboard/dashboard.models';
import { percentToBasisPoints, TOTAL_BASIS_POINTS } from '../expenses/expense.service';
import {
  PendingWrite,
  PendingWriteError,
  PendingWritesService,
  uncertainWrite,
} from '../core/pending-writes.service';
import { ExpenseService } from '../expenses/expense.service';
import {
  ContributionInput,
  CreateExpenseRequest,
  SplitInput,
  SplitType,
} from '../expenses/expense.models';
import { GroupService } from '../groups/group.service';
import { ModalService } from './modal.service';
import { ModalShellComponent } from './modal-shell.component';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { translate } from '../core/i18n/i18n.service';

const CATEGORIES: { value: ExpenseCategory; label: string; ico: string }[] = [
  {
    value: 'FOOD_DRINK',
    get label() {
      return translate('newExpenseModal.foodDrink');
    },
    ico: '☕',
  },
  {
    value: 'GROCERIES',
    get label() {
      return translate('newExpenseModal.groceries');
    },
    ico: '🧺',
  },
  {
    value: 'RENT_HOME',
    get label() {
      return translate('newExpenseModal.rentHome');
    },
    ico: '⌂',
  },
  {
    value: 'UTILITIES',
    get label() {
      return translate('newExpenseModal.utilities');
    },
    ico: '⚡',
  },
  {
    value: 'TRAVEL',
    get label() {
      return translate('newExpenseModal.travel');
    },
    ico: '✈',
  },
  {
    value: 'TRANSPORT',
    get label() {
      return translate('newExpenseModal.transport');
    },
    ico: '🚕',
  },
  {
    value: 'FUN',
    get label() {
      return translate('newExpenseModal.fun');
    },
    ico: '✦',
  },
  {
    value: 'OTHER',
    get label() {
      return translate('groupSettings.other');
    },
    ico: '◆',
  },
];

@Component({
  selector: 'app-new-expense-modal',
  imports: [TranslatePipe, ReactiveFormsModule, ModalShellComponent],
  templateUrl: './new-expense-modal.component.html',
  styleUrl: './modals.scss',
})
export class NewExpenseModalComponent {
  private readonly groups = inject(GroupService);
  private readonly profile = inject(ProfileService);
  protected get currencies() {
    return CURRENCIES;
  }
  protected readonly currency = signal<Currency>(this.profile.prefs().currency);
  protected readonly precision = computed(() => CURRENCY_EXPONENT[this.currency()]);
  protected get maxAmountMinor() {
    return MAX_TRANSACTION_MINOR;
  }
  protected readonly step = computed(() =>
    this.precision() === 0 ? '1' : '0.' + '0'.repeat(this.precision() - 1) + '1',
  );
  private currencyChosen = false;
  freezeCurrencyDefault(): void {
    this.currencyChosen = true;
  }
  selectCurrency(value: string): void {
    if (this.editId || !CURRENCIES.includes(value as Currency)) return;
    this.currencyChosen = true;
    this.currency.set(value as Currency);
    this.tick.update((n) => n + 1);
  }
  private readonly pendingWrites = inject(PendingWritesService);
  protected readonly unmappedOriginalIds = signal<string[]>([]);
  protected readonly draftBlocked = signal(false);
  protected readonly retryNotice = signal<string | null>(null);
  private restored: PendingWrite<CreateExpenseRequest> | null = null;
  private readonly expenses = inject(ExpenseService);
  private readonly recurring = inject(RecurringService);
  private readonly dashboard = inject(DashboardService);
  private readonly auth = inject(AuthService);
  private readonly modal = inject(ModalService);
  private readonly session = inject(SessionService);
  private readonly sessionVersion = this.session.version;
  private readonly destroyRef = inject(DestroyRef);

  protected readonly categories = CATEGORIES;
  protected readonly display = (value: number) => minorToDisplay(value, this.currency());

  protected readonly groupOptions = () => this.dashboard.data()?.groups ?? [];
  protected readonly groupId = signal<string | null>(null);
  private viewerId = '';
  private ownedIds: string[] = [];
  protected readonly members = signal<ParticipantSummary[]>([]);

  protected readonly description = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required],
  });
  protected readonly amount = new FormControl<string | number | null>(null);
  protected readonly date = new FormControl(this.today(), { nonNullable: true });
  /** New group expenses only: anything but NEVER creates a schedule instead of one expense. */
  protected readonly repeat = signal<Frequency | 'NEVER'>('NEVER');
  protected readonly category = signal<ExpenseCategory>('FOOD_DRINK');
  protected readonly paidBy = signal<string>('');
  protected readonly splitType = signal<SplitType>('EQUAL');

  /** Multi-payer mode: raw per-member amounts; blank excludes. paidBy stays the primary payer. */
  protected readonly multiPayer = signal(false);
  protected readonly payerValues = signal<Record<string, string>>({});
  /** The loaded expense had several payers, so its PUT must always carry contributions. */
  private hadMultiPayer = false;
  private readonly serializedContributions = computed<ContributionInput[] | null>(() => {
    const values = this.payerValues();
    const rows: ContributionInput[] = [];
    for (const member of this.members()) {
      const raw = values[member.id]?.trim() ?? '';
      if (raw === '') continue;
      const amountMinor = amountToMinor(raw, this.currency(), false);
      if (!Number.isSafeInteger(amountMinor) || amountMinor <= 0) return null;
      rows.push({ userId: member.id, amountMinor });
    }
    return rows;
  });

  /** EQUAL: member ids included. Custom modes: raw per-member input strings; blank excludes. */
  protected readonly included = signal<Set<string>>(new Set());
  protected readonly values = signal<Record<string, string>>({});
  /** Normalize once for the running total, save gate and request; retain explicit zeros. */
  private readonly serializedSplits = computed<SplitInput[] | null>(() => {
    const type = this.splitType();
    const values = this.values();
    const splits: SplitInput[] = [];
    for (const member of this.members()) {
      const raw = Number(values[member.id] ?? 0);
      const value =
        type === 'WEIGHTS'
          ? raw
          : type === 'PERCENTAGE'
            ? percentToBasisPoints(raw)
            : amountToMinor(values[member.id]?.trim() || '0', this.currency());
      if (
        !Number.isFinite(raw) ||
        (type !== 'ADJUSTMENTS' && raw < 0) ||
        !Number.isSafeInteger(value) ||
        Math.abs(value) > 1_000_000_000_000
      )
        return null;
      if (type === 'WEIGHTS' && (values[member.id]?.trim() ?? '') !== '' && value < 1) return null;
      if ((values[member.id]?.trim() ?? '') !== '') splits.push({ userId: member.id, value });
    }
    return splits;
  });
  private readonly assignedTotal = computed(() =>
    this.serializedSplits()?.reduce((sum, split) => sum + split.value, 0),
  );
  /** Bumped on every keystroke so the running-total getters re-read the form. */
  protected readonly tick = signal(0);

  protected readonly saving = signal(false);
  protected readonly ready = signal(false);
  protected readonly loadFailed = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Set when opened via "Edit" — save() then PUTs instead of POSTing. */
  protected readonly editId = this.modal.editExpenseId();
  private groupRequestVersion = 0;
  private expectedVersion: number | undefined;

  constructor() {
    effect(() => {
      const preferred = this.profile.prefs().currency;
      if (!this.editId && !this.restored && !this.currencyChosen && !this.amount.dirty)
        this.currency.set(preferred);
    });
    this.destroyRef.onDestroy(() => {
      if (this.session.version === this.sessionVersion) this.modal.setMutationPending(false);
    });
    if (!this.editId) {
      try {
        this.restored = this.pendingWrites.read<CreateExpenseRequest>('expense');
      } catch (err) {
        this.draftBlocked.set(true);
        this.retryNotice.set(
          err instanceof PendingWriteError
            ? err.message
            : translate('pendingWrites.cannotReadTheSaved'),
        );
      }
    }
    const preselect = this.restored?.scope ?? this.modal.expenseGroupId();
    const first = this.groupOptions()[0]?.id ?? null;
    this.selectGroup(preselect ?? first);
  }

  createGroup(): void {
    this.modal.openGroup();
  }

  heading(): string {
    return this.editId
      ? translate('newExpenseModal.editExpense')
      : translate('newExpenseModal.newExpense');
  }

  private today(): string {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  }

  selectGroup(id: string | null): void {
    if (this.session.version !== this.sessionVersion) return;
    if (this.saving() || (this.editId && this.groupId())) {
      return;
    }
    this.loadGroup(id);
  }

  retryMembers(): void {
    if (this.session.version !== this.sessionVersion) return;
    if (this.saving() || !this.loadFailed()) return;
    this.loadGroup(this.groupId());
  }

  private loadGroup(id: string | null): void {
    const version = ++this.groupRequestVersion;
    this.loadFailed.set(false);
    this.groupId.set(id);
    this.ready.set(false);
    this.error.set(null);
    this.members.set([]);
    this.unmappedOriginalIds.set([]);
    this.paidBy.set('');
    this.included.set(new Set());
    this.values.set({});
    this.multiPayer.set(false);
    this.payerValues.set({});
    if (!id) {
      return;
    }
    this.groups
      .getGroup(id)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (g) => {
          if (version !== this.groupRequestVersion) return;
          this.members.set(financialRoster(g));
          this.viewerId = viewerIdentity(g, this.auth.user()?.id ?? '');
          this.ownedIds = ownedIdentities(g, this.auth.user()?.id ?? '');
          if (this.editId) {
            this.prefillFromExpense(id, this.editId, version);
          } else {
            this.paidBy.set(this.viewerId || this.members()[0]?.id || '');
            this.included.set(new Set(this.members().map((m) => m.id)));
            if (this.restored?.scope === id) {
              this.restoreDraft(this.restored.request);
              this.retryNotice.set(uncertainWrite());
            }
            this.ready.set(true);
          }
        },
        error: () => {
          if (version !== this.groupRequestVersion) return;
          this.loadFailed.set(true);
          this.error.set(translate('addMemberModal.couldNotLoadGroup'));
        },
      });
  }

  /** Restore original inputs; old responses without intent are safely edited as exact shares. */
  private prefillFromExpense(groupId: string, expenseId: string, version: number): void {
    this.expenses
      .getExpense(groupId, expenseId)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (e) => {
          if (version !== this.groupRequestVersion) return;
          if (!Number.isSafeInteger(e.version) || e.version! < 0) {
            this.loadFailed.set(true);
            this.error.set(translate('newExpenseModal.reloadOrUpgradeThe'));
            return;
          }
          // Keep original alias rows and payer IDs; canonical defaults must not erase history.
          this.members.update((roster) => {
            const originals = [
              e.paidBy,
              ...e.participants.map((p) => p.user),
              ...(e.contributions ?? []).map((c) => c.user),
            ];
            return [
              ...roster,
              ...originals.filter(
                (p, i) =>
                  !roster.some((m) => m.id === p.id) &&
                  originals.findIndex((m) => m.id === p.id) === i,
              ),
            ];
          });
          this.expectedVersion = e.version;
          this.currency.set(e.currency ?? 'USD');
          if (!this.description.dirty) this.description.setValue(e.description);
          if (!this.amount.dirty)
            this.amount.setValue(minorToDisplay(e.amountMinor, this.currency()));
          if (!this.date.dirty) this.date.setValue(e.spentOn);
          this.category.set(e.category);
          this.paidBy.set(e.paidBy.id);
          if ((e.contributions?.length ?? 0) > 1) {
            this.hadMultiPayer = true;
            this.multiPayer.set(true);
            this.payerValues.set(
              Object.fromEntries(
                e.contributions!.map((c) => [
                  c.user.id,
                  minorToDisplay(c.amountMinor, this.currency()),
                ]),
              ),
            );
          }
          this.included.set(new Set(e.participantUserIds ?? e.participants.map((p) => p.user.id)));
          if (e.splitType === 'EQUAL') {
            this.splitType.set('EQUAL');
            this.values.set({});
          } else {
            this.splitType.set(e.splits ? e.splitType : 'UNEQUAL');
            const vals: Record<string, string> = {};
            if (e.splits) {
              for (const input of e.splits)
                vals[input.userId] =
                  e.splitType === 'WEIGHTS'
                    ? String(input.value)
                    : e.splitType === 'PERCENTAGE'
                      ? minorToDisplay(input.value, 'USD')
                      : this.display(input.value);
            } else {
              for (const p of e.participants) vals[p.user.id] = this.display(p.shareMinor);
            }
            this.values.set(vals);
          }
          this.tick.update((n) => n + 1);
          this.ready.set(true);
        },
        error: () => {
          if (version !== this.groupRequestVersion) return;
          this.loadFailed.set(true);
          this.error.set(translate('newExpenseModal.couldNotLoadThe'));
        },
      });
  }

  meId(): string {
    return this.viewerId;
  }

  firstName(u: ParticipantSummary): string {
    return this.ownedIds.includes(u.id)
      ? translate('dashboard.you')
      : u.displayName.split(/\s+/)[0];
  }

  sameOwner(u: ParticipantSummary): boolean {
    return (
      !!u.canonicalParticipantId &&
      this.members().some(
        (m) => m.id !== u.id && m.canonicalParticipantId === u.canonicalParticipantId,
      )
    );
  }

  memberLabel(u: ParticipantSummary): string {
    const you = this.ownedIds.includes(u.id) ? translate('newExpenseModal.youLabel') + ' ' : '';
    return `${you}${u.displayName}${u.email ? ` (${u.email})` : ' ' + translate('newExpenseModal.guest')}`;
  }

  initials(name: string): string {
    return name
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]?.toUpperCase() ?? '')
      .join('');
  }

  /** Per-person avatar color (background + text) — never the lime accent. */
  tint(id: string): { background: string; color: string } {
    return avatarTint(id);
  }

  toggle(id: string): void {
    const s = new Set(this.included());
    s.has(id) ? s.delete(id) : s.add(id);
    this.included.set(s);
  }

  setValue(id: string, v: string): void {
    this.values.update((m) => ({ ...m, [id]: v }));
    this.tick.update((n) => n + 1);
  }

  setPayerValue(id: string, v: string): void {
    this.payerValues.update((m) => ({ ...m, [id]: v }));
  }

  /** Live remainder between the expense amount and what the payers have been given. */
  payerRemainder(): string {
    this.tick();
    const rows = this.serializedContributions();
    if (!rows) return translate('newExpenseModal.enterValidPositiveAmounts');
    const diff = this.amountMinor() - rows.reduce((sum, r) => sum + r.amountMinor, 0);
    if (!Number.isSafeInteger(diff)) return translate('newExpenseModal.enterTheExpenseAmount');
    if (diff === 0) return translate('newExpenseModal.payerAmountsMatchThe');
    return diff > 0
      ? translate('newExpenseModal.diffLeftToAssign', { diff: this.display(diff) })
      : translate('newExpenseModal.v1Over', { v1: this.display(-diff) });
  }

  /** Nonempty, positive and unique by construction; must include the primary payer and sum to the amount. */
  payersValid(): boolean {
    this.tick();
    if (!this.multiPayer()) return true;
    const rows = this.serializedContributions();
    return (
      !!rows?.length &&
      rows.some((r) => r.userId === this.paidBy()) &&
      rows.reduce((sum, r) => sum + r.amountMinor, 0) === this.amountMinor()
    );
  }

  /** Undefined keeps the single-payer wire shape; a former multi-payer edit never omits it. */
  private contributionsForRequest(): ContributionInput[] | undefined {
    if (this.multiPayer()) return this.serializedContributions()!;
    return this.hadMultiPayer
      ? [{ userId: this.paidBy(), amountMinor: this.amountMinor() }]
      : undefined;
  }

  private amountMinor(): number {
    return amountToMinor(this.amount.value ?? '0', this.currency(), false);
  }

  runningLabel(): string {
    this.tick();
    if (this.splitType() === 'EQUAL') {
      return translate('newExpenseModal.splitEquallyAmongSize', { size: this.included().size });
    }
    if (this.splitType() === 'ADJUSTMENTS') {
      return this.adjustmentsValid()
        ? translate('newExpenseModal.adjustmentsTotalCurrencyFinal', {
            currency: formatMoney(0, false, this.currency()),
          })
        : translate('newExpenseModal.adjustmentsMustTotalCurrency', {
            currency: formatMoney(0, false, this.currency()),
          });
    }
    if (this.splitType() === 'WEIGHTS') {
      const splits = this.serializedSplits();
      return splits
        ? translate('newExpenseModal.weightUnitsTotal', {
            total: String(splits.reduce((sum, s) => sum + BigInt(s.value), 0n)),
          })
        : translate('newExpenseModal.enterPositiveWholeWeight');
    }
    const total = this.assignedTotal();
    if (total === undefined) return translate('newExpenseModal.enterValidNonnegativeShares');
    if (this.splitType() === 'PERCENTAGE') {
      return `${minorToDisplay(total, 'USD')}% / 100%`;
    }
    return `${formatMoney(total, false, this.currency())} / ${formatMoney(this.amountMinor(), false, this.currency())}`;
  }

  splitValid(): boolean {
    this.tick();
    if (this.splitType() === 'EQUAL') {
      return this.included().size >= 1;
    }
    if (this.splitType() === 'ADJUSTMENTS') return this.adjustmentsValid();
    if (this.splitType() === 'WEIGHTS') return (this.serializedSplits()?.length ?? 0) > 0;
    const total = this.assignedTotal();
    if (total === undefined || !Number.isSafeInteger(total)) return false;
    if (this.splitType() === 'PERCENTAGE') {
      return total === TOTAL_BASIS_POINTS;
    }
    return this.amountMinor() > 0 && total === this.amountMinor();
  }

  private adjustmentsValid(): boolean {
    const splits = this.serializedSplits();
    const amount = this.amountMinor();
    if (!splits?.length || !Number.isSafeInteger(amount) || amount <= 0) return false;
    if (splits.reduce((sum, s) => sum + BigInt(s.value), 0n) !== 0n) return false;
    const base = Math.floor(amount / splits.length);
    const remainder = amount % splits.length;
    const recipient = splits.some((s) => s.userId === this.paidBy())
      ? this.paidBy()
      : [...splits].sort((a, b) => this.compareUuid(a.userId, b.userId))[0].userId;
    return splits.every((s) => base + (s.userId === recipient ? remainder : 0) + s.value >= 0);
  }

  /** Match java.util.UUID's signed high/low ordering for the existing remainder policy. */
  private compareUuid(a: string, b: string): number {
    const left = a.replaceAll('-', '');
    const right = b.replaceAll('-', '');
    for (const offset of [0, 16]) {
      const x = BigInt.asIntN(64, BigInt(`0x${left.slice(offset, offset + 16)}`));
      const y = BigInt.asIntN(64, BigInt(`0x${right.slice(offset, offset + 16)}`));
      if (x !== y) return x < y ? -1 : 1;
    }
    return 0;
  }

  canSave(): boolean {
    return (
      !!this.groupId() &&
      this.ready() &&
      !this.draftBlocked() &&
      this.unmappedOriginalIds().length === 0 &&
      this.description.valid &&
      Number.isSafeInteger(this.amountMinor()) &&
      this.amountMinor() > 0 &&
      this.amountMinor() <= 1_000_000_000_000 &&
      !!this.paidBy() &&
      this.splitValid() &&
      this.payersValid() &&
      !this.saving()
    );
  }

  close(): void {
    if (this.session.version !== this.sessionVersion) return;
    this.modal.requestClose();
  }

  save(): void {
    if (this.session.version !== this.sessionVersion) return;
    if (!this.canSave()) {
      return;
    }
    const gid = this.groupId()!;
    const base = {
      description: this.description.value.trim(),
      amountMinor: this.amountMinor(),
      currency: this.currency(),
      paidByUserId: this.paidBy(),
      category: this.category(),
      spentOn: this.date.value,
    };
    const contributions = this.contributionsForRequest();
    const paid = contributions ? { ...base, contributions } : base;
    let req: CreateExpenseRequest;
    if (this.splitType() === 'EQUAL') {
      req = { ...paid, participantUserIds: [...this.included()] };
    } else {
      req = { ...paid, splitType: this.splitType(), splits: this.serializedSplits()! };
    }
    const frequency = this.repeat();
    if (!this.editId && frequency !== 'NEVER') {
      this.saveSchedule(gid, req, frequency);
      return;
    }
    this.dispatch(gid, this.editId ? { ...req, expectedVersion: this.expectedVersion } : req);
  }

  private saveSchedule(gid: string, template: CreateExpenseRequest, frequency: Frequency): void {
    const intent = {
      template,
      frequency,
      anchorDate: this.date.value,
      timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    };
    // One operationId per identical uncertain draft, so a retry cannot add a second schedule.
    let pending: PendingWrite<typeof intent>;
    try {
      try {
        pending = this.pendingWrites.prepare('recurring', gid, intent);
      } catch (error) {
        if ((error as Error).message !== uncertainWrite()) throw error;
        this.pendingWrites.abandon('recurring'); // the draft changed: a new schedule, a new key
        pending = this.pendingWrites.prepare('recurring', gid, intent);
      }
    } catch (error) {
      this.error.set((error as Error).message);
      return;
    }
    this.saving.set(true);
    this.modal.setMutationPending(true);
    this.error.set(null);
    this.recurring
      .create(gid, { ...intent, operationId: pending.operationId })
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.pendingWrites.complete('recurring', pending);
          this.dashboard.refresh();
          this.modal.close();
        },
        error: (err) => {
          this.error.set(err?.error?.message ?? translate('newExpenseModal.couldNotSaveThe'));
          this.saving.set(false);
          this.modal.setMutationPending(false);
        },
      });
  }

  private dispatch(gid: string, req: CreateExpenseRequest): void {
    let pending: PendingWrite<CreateExpenseRequest> | null = null;
    if (!this.editId) {
      try {
        pending = this.pendingWrites.prepare('expense', gid, req);
        this.restored = pending;
      } catch (error) {
        this.error.set((error as Error).message);
        this.retryNotice.set(uncertainWrite());
        return;
      }
    }
    this.saving.set(true);
    this.modal.setMutationPending(true);
    this.error.set(null);
    const save$ = this.editId
      ? this.expenses.updateExpense(gid, this.editId, req)
      : this.expenses.createExpense(gid, { ...req, operationId: pending!.operationId });
    save$.pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        if (pending) this.pendingWrites.complete('expense', pending);
        this.dashboard.refresh();
        this.modal.close();
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? translate('newExpenseModal.couldNotSaveThe2'));
        if (!this.editId) this.retryNotice.set(uncertainWrite());
        this.saving.set(false);
        this.modal.setMutationPending(false);
      },
    });
  }
  retryOriginal(): void {
    if (
      this.saving() ||
      !this.ready() ||
      this.session.version !== this.sessionVersion ||
      !this.restored
    )
      return;
    this.dispatch(this.restored.scope, this.restored.request);
  }

  abandonRetry(): void {
    if (this.saving() || this.session.version !== this.sessionVersion) return;
    if (!confirm(translate('newExpenseModal.thePreviousExpenseMay'))) return;
    try {
      this.pendingWrites.abandon('expense');
      this.restored = null;
      this.retryNotice.set(null);
      this.draftBlocked.set(false);
      this.unmappedOriginalIds.set([]);
      this.error.set(null);
    } catch {
      this.error.set(translate('personalExpenses.cannotClearTheSaved'));
    }
  }

  private restoreDraft(request: CreateExpenseRequest): void {
    const originalIds = [
      request.paidByUserId,
      ...(request.contributions ?? []).map((c) => c.userId),
      ...(request.participantUserIds ?? []),
      ...(request.splits ?? []).map((s) => s.userId),
    ];
    this.unmappedOriginalIds.set(
      [...new Set(originalIds)].filter((id) => !this.members().some((p) => p.id === id)),
    );
    this.description.setValue(request.description);
    this.currency.set(request.currency ?? 'USD');
    this.amount.setValue(
      minorToDisplay(request.amountMinor ?? request.amountCents!, this.currency()),
    );
    this.date.setValue(request.spentOn ?? this.today());
    this.category.set(request.category ?? 'OTHER');
    this.paidBy.set(request.paidByUserId);
    if (request.contributions) {
      this.multiPayer.set(true);
      this.payerValues.set(
        Object.fromEntries(
          request.contributions.map((c) => [
            c.userId,
            minorToDisplay(c.amountMinor, this.currency()),
          ]),
        ),
      );
    }
    this.splitType.set(request.splitType ?? 'EQUAL');
    this.included.set(new Set(request.participantUserIds ?? []));
    this.values.set(
      Object.fromEntries(
        (request.splits ?? []).map((input) => [
          input.userId,
          request.splitType === 'WEIGHTS'
            ? String(input.value)
            : request.splitType === 'PERCENTAGE'
              ? minorToDisplay(input.value, 'USD')
              : this.display(input.value),
        ]),
      ),
    );
    this.tick.update((n) => n + 1);
  }
}
