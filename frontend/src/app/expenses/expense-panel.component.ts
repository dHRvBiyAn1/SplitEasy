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
import { CreateExpenseRequest, ExpenseSummary, SplitType } from './expense.models';
import { ExpenseService, centsToDisplay, dollarsToCents, percentToBasisPoints } from './expense.service';

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
  /** Emitted after an expense is successfully created, so parents can refresh balances. */
  readonly expenseAdded = output<void>();

  private readonly fb = inject(FormBuilder);
  private readonly expenses = inject(ExpenseService);

  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly list = signal<ExpenseSummary[]>([]);

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
    const members = this.group().members;
    this.selected.set(new Set(members.map((m) => m.id)));
    this.form.patchValue({ paidByUserId: members[0]?.id ?? '' });
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

  setSplitType(type: SplitType): void {
    this.splitType.set(type);
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
  private entered(): { userId: string; value: number }[] {
    return this.group()
      .members.map((m) => ({ userId: m.id, value: this.values().get(m.id) }))
      .filter((e): e is { userId: string; value: number } => e.value != null && Number.isFinite(e.value));
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
        return this.entered().length > 0 && this.runningBasisPoints() === 10000;
    }
  }

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

    this.expenses.createExpense(this.group().id, request).subscribe({
      next: (created) => {
        this.list.update((rows) => [
          {
            id: created.id,
            description: created.description,
            amountCents: created.amountCents,
            paidBy: created.paidBy,
            participantCount: created.participants.length,
            createdAt: created.createdAt,
          },
          ...rows,
        ]);
        this.form.reset({ description: '', amount: null, paidByUserId: members[0]?.id ?? '' });
        this.selected.set(new Set(members.map((m) => m.id)));
        this.values.set(new Map());
        this.saving.set(false);
        this.expenseAdded.emit();
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? 'Could not add the expense.');
        this.saving.set(false);
      },
    });
  }
}
