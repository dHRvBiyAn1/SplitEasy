import { Currency, amountToMinor, minorToDisplay } from '../shared/currency';
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

  updateExpense(
    groupId: string,
    expenseId: string,
    request: CreateExpenseRequest,
  ): Observable<ExpenseResponse> {
    return this.api.put<ExpenseResponse>(`/groups/${groupId}/expenses/${expenseId}`, request);
  }

  deleteExpense(groupId: string, expenseId: string, expectedVersion?: number): Observable<void> {
    return this.api.delete<void>(
      `/groups/${groupId}/expenses/${expenseId}${expectedVersion === undefined ? '' : `?expectedVersion=${expectedVersion}`}`,
    );
  }
}

/** Dollars (as typed by the user) → integer cents, avoiding binary-float drift. */
export function dollarsToCents(dollars: number): number {
  return amountToMinor(dollars, 'USD');
}

/** 100% expressed in basis points (hundredths of a percent) — the wire contract for splits. */
export const TOTAL_BASIS_POINTS = 10_000;

/** Percent (as typed, e.g. 33.33) → integer basis points (3333). Sum must be TOTAL_BASIS_POINTS. */
export function percentToBasisPoints(percent: number): number {
  return Math.round(percent * 100);
}

/** Integer cents → a display string like "12.34". */
export function centsToDisplay(minor: number, currency: Currency = 'USD'): string {
  return minorToDisplay(minor, currency);
}
