import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatListModule } from '@angular/material/list';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { GroupResponse } from '../groups/group.models';
import { ExpenseSummary } from './expense.models';
import { ExpenseService, centsToDisplay, dollarsToCents } from './expense.service';

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
    MatListModule,
    MatProgressBarModule,
  ],
  templateUrl: './expense-panel.component.html',
  styleUrl: './expense-panel.component.scss',
})
export class ExpensePanelComponent implements OnInit {
  readonly group = input.required<GroupResponse>();

  private readonly fb = inject(FormBuilder);
  private readonly expenses = inject(ExpenseService);

  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly list = signal<ExpenseSummary[]>([]);

  // Participant selection is a set of user ids; defaults to everyone.
  protected readonly selected = signal<Set<string>>(new Set());
  protected readonly hasParticipants = computed(() => this.selected().size > 0);

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

  submit(): void {
    if (this.form.invalid || !this.hasParticipants() || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    const raw = this.form.getRawValue();
    const members = this.group().members;
    const participantUserIds = members.map((m) => m.id).filter((id) => this.selected().has(id));

    this.expenses
      .createExpense(this.group().id, {
        description: raw.description,
        amountCents: dollarsToCents(raw.amount ?? 0),
        paidByUserId: raw.paidByUserId,
        participantUserIds,
      })
      .subscribe({
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
          this.saving.set(false);
        },
        error: (err) => {
          this.error.set(err?.error?.message ?? 'Could not add the expense.');
          this.saving.set(false);
        },
      });
  }
}
