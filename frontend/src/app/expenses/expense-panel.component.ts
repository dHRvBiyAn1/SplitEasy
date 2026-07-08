import { Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatListModule } from '@angular/material/list';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { GroupResponse } from '../groups/group.models';
import { CreateExpenseRequest, ExpenseResponse, ExpenseSummary, SplitType } from './expense.models';
import {
  ExpenseService,
  TOTAL_BASIS_POINTS,
  centsToDisplay,
  dollarsToCents,
  percentToBasisPoints,
} from './expense.service';

/** A member who has a finite numeric value entered in an UNEQUAL/PERCENTAGE split. */
interface EnteredValue {
  userId: string;
  value: number;
}

@Component({
  selector: 'app-expense-panel',
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatListModule,
    MatProgressBarModule,
  ],
  templateUrl: './expense-panel.component.html',
  styleUrl: './expense-panel.component.scss',
})
export class ExpensePanelComponent implements OnInit {
  readonly group = input.required<GroupResponse>();
  /** Emitted after any add/edit/delete, so parents can refresh balances. */
  readonly expensesChanged = output<void>();

  private readonly fb = inject(FormBuilder);
  private readonly expenses = inject(ExpenseService);

  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly list = signal<ExpenseSummary[]>([]);

  /** Non-null when editing an existing expense (submit does PUT instead of POST). */
  protected readonly editingId = signal<string | null>(null);
  /** Id of the expense showing an inline delete confirmation, if any. */
  protected readonly confirmingDeleteId = signal<string | null>(null);

  protected readonly splitType = signal<SplitType>('EQUAL');
  // EQUAL: which members share the expense.
  protected readonly selected = signal<Set<string>>(new Set());
  protected readonly hasParticipants = computed(() => this.selected().size > 0);
  // UNEQUAL/PERCENTAGE: per-member typed value (dollars or percent); null = not entered.
  protected readonly values = signal<Map<string, number | null>>(new Map());

  protected readonly form = this.fb.nonNullable.group({
    description: ['', [Validators.required, Validators.maxLength(200)]],
    amount: [null as number | null, [Validators.required, Validators.min(0.01)]],
    paidByUserId: ['', [Validators.required]],
  });

  protected readonly display = centsToDisplay;

  ngOnInit(): void {
    this.resetForm();
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.expenses.listExpenses(this.group().id).subscribe({
      next: (rows) => {
        this.list.set(rows);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load expenses.');
        this.loading.set(false);
      },
    });
  }

  private resetForm(): void {
    const members = this.group().members;
    this.editingId.set(null);
    this.splitType.set('EQUAL');
    this.selected.set(new Set(members.map((m) => m.id)));
    this.values.set(new Map());
    this.form.reset({ description: '', amount: null, paidByUserId: members[0]?.id ?? '' });
  }

  setSplitType(type: SplitType): void {
    if (type === this.splitType()) {
      return;
    }
    this.splitType.set(type);
    // Clear per-member entries carried over from the previous type — a UNEQUAL dollar
    // amount is meaningless as a PERCENTAGE (and vice versa), and stale entries were
    // previously kept and silently filtered. Toggling now starts the split fresh.
    this.values.set(new Map());
  }

  toggle(userId: string, checked: boolean): void {
    this.selected.update((set) => {
      const next = new Set(set);
      if (checked) {
        next.add(userId);
      } else {
        next.delete(userId);
      }
      return next;
    });
  }

  isSelected(userId: string): boolean {
    return this.selected().has(userId);
  }

  setValue(userId: string, raw: string): void {
    const n = raw === '' ? null : Number(raw);
    this.values.update((m) => {
      const next = new Map(m);
      next.set(userId, n != null && Number.isFinite(n) ? n : null);
      return next;
    });
  }

  valueOf(userId: string): number | null {
    return this.values().get(userId) ?? null;
  }

  private amountCents(): number {
    return dollarsToCents(this.form.getRawValue().amount ?? 0);
  }

  /** Members with a numeric value entered (for UNEQUAL/PERCENTAGE). */
  private entered(): EnteredValue[] {
    return this.group()
      .members.map((m) => ({ userId: m.id, value: this.values().get(m.id) }))
      .filter((e): e is EnteredValue => e.value != null && Number.isFinite(e.value));
  }

  private runningCents(): number {
    return this.entered().reduce((sum, e) => sum + dollarsToCents(e.value), 0);
  }

  private runningBasisPoints(): number {
    return this.entered().reduce((sum, e) => sum + percentToBasisPoints(e.value), 0);
  }

  /** Live feedback so invalid splits are visible before submit. */
  runningLabel(): string {
    if (this.splitType() === 'UNEQUAL') {
      return `Total entered: $${centsToDisplay(this.runningCents())} / $${centsToDisplay(this.amountCents())}`;
    }
    if (this.splitType() === 'PERCENTAGE') {
      return `${(this.runningBasisPoints() / 100).toFixed(2)}% / 100%`;
    }
    return '';
  }

  splitValid(): boolean {
    switch (this.splitType()) {
      case 'EQUAL':
        return this.hasParticipants();
      case 'UNEQUAL':
        return this.entered().length > 0 && this.runningCents() === this.amountCents();
      case 'PERCENTAGE':
        return this.entered().length > 0 && this.runningBasisPoints() === TOTAL_BASIS_POINTS;
    }
  }

  // --- edit ---

  startEdit(summary: ExpenseSummary): void {
    this.error.set(null);
    this.confirmingDeleteId.set(null);
    // The list only has summaries; fetch full shares to prefill the form.
    this.expenses.getExpense(this.group().id, summary.id).subscribe({
      next: (e) => this.prefill(e),
      error: () => this.error.set('Could not load that expense for editing.'),
    });
  }

  private prefill(e: ExpenseResponse): void {
    this.editingId.set(e.id);
    this.splitType.set(e.splitType);
    this.form.setValue({
      description: e.description,
      amount: e.amountCents / 100,
      paidByUserId: e.paidBy.id,
    });
    if (e.splitType === 'EQUAL') {
      this.selected.set(new Set(e.participants.map((p) => p.user.id)));
      this.values.set(new Map());
    } else {
      const map = new Map<string, number | null>();
      for (const p of e.participants) {
        // UNEQUAL: exact dollars. PERCENTAGE: best-effort percent from cents (lossy — the
        // running-total gate blocks an invalid resubmit; the user can adjust).
        const prefillValue =
          e.splitType === 'UNEQUAL'
            ? p.shareCents / 100
            : Number(((p.shareCents / e.amountCents) * 100).toFixed(2));
        map.set(p.user.id, prefillValue);
      }
      this.values.set(map);
    }
    this.selected.update((s) => new Set(s)); // ensure signal fires for EQUAL path
  }

  cancelEdit(): void {
    this.resetForm();
    this.error.set(null);
  }

  // --- delete (inline confirm) ---

  askDelete(id: string): void {
    this.confirmingDeleteId.set(id);
  }

  cancelDelete(): void {
    this.confirmingDeleteId.set(null);
  }

  confirmDelete(id: string): void {
    this.expenses.deleteExpense(this.group().id, id).subscribe({
      next: () => {
        this.list.update((rows) => rows.filter((r) => r.id !== id));
        this.confirmingDeleteId.set(null);
        if (this.editingId() === id) {
          this.resetForm();
        }
        this.expensesChanged.emit();
      },
      error: () => this.error.set('Could not delete that expense.'),
    });
  }

  // --- submit (add or edit) ---

  submit(): void {
    if (this.form.invalid || !this.splitValid() || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    const raw = this.form.getRawValue();
    const members = this.group().members;
    const type = this.splitType();

    const request: CreateExpenseRequest = {
      description: raw.description,
      amountCents: this.amountCents(),
      paidByUserId: raw.paidByUserId,
      splitType: type,
      participantUserIds:
        type === 'EQUAL' ? members.map((m) => m.id).filter((id) => this.selected().has(id)) : null,
      splits:
        type === 'EQUAL'
          ? null
          : this.entered().map((e) => ({
              userId: e.userId,
              value: type === 'UNEQUAL' ? dollarsToCents(e.value) : percentToBasisPoints(e.value),
            })),
    };

    const editing = this.editingId();
    const call = editing
      ? this.expenses.updateExpense(this.group().id, editing, request)
      : this.expenses.createExpense(this.group().id, request);

    call.subscribe({
      next: (saved) => {
        const summary: ExpenseSummary = {
          id: saved.id,
          description: saved.description,
          amountCents: saved.amountCents,
          paidBy: saved.paidBy,
          participantCount: saved.participants.length,
          createdAt: saved.createdAt,
        };
        if (editing) {
          this.list.update((rows) => rows.map((r) => (r.id === editing ? summary : r)));
        } else {
          this.list.update((rows) => [summary, ...rows]);
        }
        this.resetForm();
        this.saving.set(false);
        this.expensesChanged.emit();
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? 'Could not save the expense.');
        this.saving.set(false);
      },
    });
  }
}
