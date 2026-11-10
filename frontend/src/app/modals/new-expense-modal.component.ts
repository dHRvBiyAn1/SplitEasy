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
  UNCERTAIN_WRITE,
} from '../core/pending-writes.service';
import { ExpenseService } from '../expenses/expense.service';
import { CreateExpenseRequest, SplitInput, SplitType } from '../expenses/expense.models';
import { GroupService } from '../groups/group.service';
import { ModalService } from './modal.service';
import { ModalShellComponent } from './modal-shell.component';

const CATEGORIES: { value: ExpenseCategory; label: string; ico: string }[] = [
  { value: 'FOOD_DRINK', label: 'Food & drink', ico: '☕' },
  { value: 'GROCERIES', label: 'Groceries', ico: '🧺' },
  { value: 'RENT_HOME', label: 'Rent & home', ico: '⌂' },
  { value: 'UTILITIES', label: 'Utilities', ico: '⚡' },
  { value: 'TRAVEL', label: 'Travel', ico: '✈' },
  { value: 'TRANSPORT', label: 'Transport', ico: '🚕' },
  { value: 'FUN', label: 'Fun', ico: '✦' },
  { value: 'OTHER', label: 'Other', ico: '◆' },
];

@Component({
  selector: 'app-new-expense-modal',
  imports: [ReactiveFormsModule, ModalShellComponent],
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
  protected readonly category = signal<ExpenseCategory>('FOOD_DRINK');
  protected readonly paidBy = signal<string>('');
  protected readonly splitType = signal<SplitType>('EQUAL');

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
            : 'Cannot read the saved retry draft. Check history before starting a separate entry.',
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
    return this.editId ? 'Edit expense' : 'New expense';
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
              this.retryNotice.set(UNCERTAIN_WRITE);
            }
            this.ready.set(true);
          }
        },
        error: () => {
          if (version !== this.groupRequestVersion) return;
          this.loadFailed.set(true);
          this.error.set('Could not load group members.');
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
            this.error.set('Reload or upgrade the app to edit this expense safely.');
            return;
          }
          // Keep original alias rows and payer IDs; canonical defaults must not erase history.
          this.members.update((roster) => {
            const originals = [e.paidBy, ...e.participants.map((p) => p.user)];
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
          this.error.set('Could not load the expense.');
        },
      });
  }

  meId(): string {
    return this.viewerId;
  }

  firstName(u: ParticipantSummary): string {
    return this.ownedIds.includes(u.id) ? 'You' : u.displayName.split(/\s+/)[0];
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
    return `${this.ownedIds.includes(u.id) ? 'You: ' : ''}${u.displayName}${u.email ? ` (${u.email})` : ' (guest)'}`;
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

  private amountMinor(): number {
    return amountToMinor(this.amount.value ?? '0', this.currency(), false);
  }

  runningLabel(): string {
    this.tick();
    if (this.splitType() === 'EQUAL') {
      return `Split equally among ${this.included().size}`;
    }
    if (this.splitType() === 'ADJUSTMENTS') {
      return this.adjustmentsValid()
        ? `Adjustments total ${formatMoney(0, false, this.currency())}; final shares nonnegative`
        : `Adjustments must total ${formatMoney(0, false, this.currency())} and leave final shares nonnegative`;
    }
    if (this.splitType() === 'WEIGHTS') {
      const splits = this.serializedSplits();
      return splits
        ? `${splits.reduce((sum, s) => sum + BigInt(s.value), 0n)} weight units`
        : 'Enter positive whole weight units';
    }
    const total = this.assignedTotal();
    if (total === undefined) return 'Enter valid nonnegative shares';
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
    let req: CreateExpenseRequest;
    if (this.splitType() === 'EQUAL') {
      req = { ...base, participantUserIds: [...this.included()] };
    } else {
      req = { ...base, splitType: this.splitType(), splits: this.serializedSplits()! };
    }
    this.dispatch(gid, this.editId ? { ...req, expectedVersion: this.expectedVersion } : req);
  }

  private dispatch(gid: string, req: CreateExpenseRequest): void {
    let pending: PendingWrite<CreateExpenseRequest> | null = null;
    if (!this.editId) {
      try {
        pending = this.pendingWrites.prepare('expense', gid, req);
        this.restored = pending;
      } catch (error) {
        this.error.set((error as Error).message);
        this.retryNotice.set(UNCERTAIN_WRITE);
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
        this.error.set(err?.error?.message ?? 'Could not save the expense.');
        if (!this.editId) this.retryNotice.set(UNCERTAIN_WRITE);
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
    if (
      !confirm(
        'The previous expense may already have been saved. Check expense history first. Start a separate expense anyway?',
      )
    )
      return;
    try {
      this.pendingWrites.abandon('expense');
      this.restored = null;
      this.retryNotice.set(null);
      this.draftBlocked.set(false);
      this.unmappedOriginalIds.set([]);
      this.error.set(null);
    } catch {
      this.error.set(
        'Cannot clear the saved retry identity. Enable browser storage and try again.',
      );
    }
  }

  private restoreDraft(request: CreateExpenseRequest): void {
    const originalIds = [
      request.paidByUserId,
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
