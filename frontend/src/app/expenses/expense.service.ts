import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../core/api/api.service';
import { CreateExpenseRequest, ExpenseResponse, ExpenseSummary } from './expense.models';

/** Expenses are nested under a group. One service per backend resource (AGENTS.md). */
@Injectable({ providedIn: 'root' })
export class ExpenseService {
  private readonly api = inject(ApiService);

  listExpenses(groupId: string): Observable<ExpenseSummary[]> {
    return this.api.get<ExpenseSummary[]>(`/groups/${groupId}/expenses`);
  }

  getExpense(groupId: string, expenseId: string): Observable<ExpenseResponse> {
    return this.api.get<ExpenseResponse>(`/groups/${groupId}/expenses/${expenseId}`);
  }

  createExpense(groupId: string, request: CreateExpenseRequest): Observable<ExpenseResponse> {
    return this.api.post<ExpenseResponse>(`/groups/${groupId}/expenses`, request);
  }
}

/** Dollars (as typed by the user) → integer cents, avoiding binary-float drift. */
export function dollarsToCents(dollars: number): number {
  return Math.round(dollars * 100);
}

/** Percent (as typed, e.g. 33.33) → integer basis points (3333). Sum must be 10000 for a valid split. */
export function percentToBasisPoints(percent: number): number {
  return Math.round(percent * 100);
}

/** Integer cents → a display string like "12.34". */
export function centsToDisplay(cents: number): string {
  const sign = cents < 0 ? '-' : '';
  const abs = Math.abs(cents);
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}
