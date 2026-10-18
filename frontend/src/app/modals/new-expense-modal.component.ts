import { Component, computed, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { takeUntil } from 'rxjs';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';
import { avatarTint } from '../core/avatar';
import { UserSummary } from '../core/auth/auth.models';
import { DashboardService } from '../dashboard/dashboard.service';
import { ExpenseCategory } from '../dashboard/dashboard.models';
import {
  centsToDisplay,
  dollarsToCents,
  percentToBasisPoints,
  TOTAL_BASIS_POINTS,
} from '../expenses/expense.service';
import { ExpenseService } from '../expenses/expense.service';
import { CreateExpenseRequest, SplitInput } from '../expenses/expense.models';
import { GroupService } from '../groups/group.service';
import { ModalService } from './modal.service';
import { ModalShellComponent } from './modal-shell.component';

type SplitType = 'EQUAL' | 'UNEQUAL' | 'PERCENTAGE';

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
  private readonly expenses = inject(ExpenseService);
  private readonly dashboard = inject(DashboardService);
  private readonly auth = inject(AuthService);
  private readonly modal = inject(ModalService);
  private readonly session = inject(SessionService);

  protected readonly categories = CATEGORIES;
  protected readonly display = centsToDisplay;

  protected readonly groupOptions = () => this.dashboard.data()?.groups ?? [];
  protected readonly groupId = signal<string | null>(null);
  protected readonly members = signal<UserSummary[]>([]);

  protected readonly description = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required],
  });
  protected readonly amount = new FormControl<number | null>(null);
  protected readonly date = new FormControl(this.today(), { nonNullable: true });
  protected readonly category = signal<ExpenseCategory>('FOOD_DRINK');
  protected readonly paidBy = signal<string>('');
  protected readonly splitType = signal<SplitType>('EQUAL');

  /** EQUAL: member ids included. UNEQUAL/PERCENTAGE: raw per-member input strings. */
  protected readonly included = signal<Set<string>>(new Set());
  protected readonly values = signal<Record<string, string>>({});
  /** Round each nonnegative finite input to the nearest cent/basis point once.
   * Keep the same positive shares for the running total, save gate and request. */
  private readonly serializedSplits = computed<SplitInput[] | null>(() => {
    const convert = this.splitType() === 'PERCENTAGE' ? percentToBasisPoints : dollarsToCents;
    const values = this.values();
    const splits: SplitInput[] = [];
    for (const member of this.members()) {
      const raw = Number(values[member.id] ?? 0);
      const value = convert(raw);
      if (!Number.isFinite(raw) || raw < 0 || !Number.isSafeInteger(value)) return null;
      if (value > 0) splits.push({ userId: member.id, value });
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
  protected readonly error = signal<string | null>(null);

  /** Set when opened via "Edit" — save() then PUTs instead of POSTing. */
  protected readonly editId = this.modal.editExpenseId();
  private groupRequestVersion = 0;

  constructor() {
    const preselect = this.modal.expenseGroupId();
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
    if (this.saving() || (this.editId && this.groupId())) {
      return;
    }
    const version = ++this.groupRequestVersion;
    this.groupId.set(id);
    this.ready.set(false);
    this.error.set(null);
    this.members.set([]);
    this.paidBy.set('');
    this.included.set(new Set());
    this.values.set({});
    if (!id) {
      return;
    }
    this.groups
      .getGroup(id)
      .pipe(takeUntil(this.session.changes))
      .subscribe({
        next: (g) => {
          if (version !== this.groupRequestVersion) return;
          this.members.set(g.members);
          if (this.editId) {
            this.prefillFromExpense(id, this.editId);
          } else {
            this.paidBy.set(this.auth.user()?.id ?? g.members[0]?.id ?? '');
            this.included.set(new Set(g.members.map((m) => m.id)));
            this.ready.set(true);
          }
        },
        error: () => {
          if (version !== this.groupRequestVersion) return;
          this.error.set('Could not load group members.');
        },
      });
  }

  /** Load an existing expense into the form. Any custom split is re-edited as exact
   * amounts (UNEQUAL) — lossless and always valid, so we never reconstruct percentages. */
  private prefillFromExpense(groupId: string, expenseId: string): void {
    this.expenses
      .getExpense(groupId, expenseId)
      .pipe(takeUntil(this.session.changes))
      .subscribe({
        next: (e) => {
          this.description.setValue(e.description);
          this.amount.setValue(e.amountCents / 100);
          this.date.setValue(e.spentOn);
          this.category.set(e.category);
          this.paidBy.set(e.paidBy.id);
          this.included.set(new Set(e.participants.map((p) => p.user.id)));
          if (e.splitType === 'EQUAL') {
            this.splitType.set('EQUAL');
            this.values.set({});
          } else {
            this.splitType.set('UNEQUAL');
            const vals: Record<string, string> = {};
            for (const p of e.participants) {
              vals[p.user.id] = this.display(p.shareCents);
            }
            this.values.set(vals);
          }
          this.tick.update((n) => n + 1);
          this.ready.set(true);
        },
        error: () => this.error.set('Could not load the expense.'),
      });
  }

  meId(): string {
    return this.auth.user()?.id ?? '';
  }

  firstName(u: UserSummary): string {
    return u.id === this.meId() ? 'You' : u.displayName.split(/\s+/)[0];
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

  private amountCents(): number {
    return dollarsToCents(this.amount.value ?? 0);
  }

  runningLabel(): string {
    this.tick();
    if (this.splitType() === 'EQUAL') {
      return `Split equally among ${this.included().size}`;
    }
    const total = this.assignedTotal();
    if (total === undefined) return 'Enter valid nonnegative shares';
    if (this.splitType() === 'PERCENTAGE') {
      return `${this.display(total)}% / 100%`;
    }
    return `$${this.display(total)} / $${this.display(this.amountCents())}`;
  }

  splitValid(): boolean {
    this.tick();
    if (this.splitType() === 'EQUAL') {
      return this.included().size >= 1;
    }
    const total = this.assignedTotal();
    if (total === undefined || !Number.isSafeInteger(total)) return false;
    if (this.splitType() === 'PERCENTAGE') {
      return total === TOTAL_BASIS_POINTS;
    }
    return this.amountCents() > 0 && total === this.amountCents();
  }

  canSave(): boolean {
    return (
      !!this.groupId() &&
      this.ready() &&
      this.description.valid &&
      this.amountCents() > 0 &&
      !!this.paidBy() &&
      this.splitValid() &&
      !this.saving()
    );
  }

  close(): void {
    this.modal.close();
  }

  save(): void {
    if (!this.canSave()) {
      return;
    }
    const gid = this.groupId()!;
    const base = {
      description: this.description.value.trim(),
      amountCents: this.amountCents(),
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
    this.saving.set(true);
    this.error.set(null);
    const save$ = this.editId
      ? this.expenses.updateExpense(gid, this.editId, req)
      : this.expenses.createExpense(gid, req);
    save$.pipe(takeUntil(this.session.changes)).subscribe({
      next: () => {
        this.dashboard.refresh();
        this.modal.close();
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? 'Could not save the expense.');
        this.saving.set(false);
      },
    });
  }
}
