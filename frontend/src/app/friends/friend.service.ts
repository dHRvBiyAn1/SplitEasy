import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../core/api/api.service';
import {
  CreateExpenseRequest,
  DeletedExpense,
  ExpenseResponse,
  ExpenseSummary,
} from '../expenses/expense.models';
import { FriendDetail, FriendSummary } from './friend.models';
@Injectable({ providedIn: 'root' })
export class FriendService {
  private readonly api = inject(ApiService);
  remove(id: string) {
    return this.api.delete<void>(`/friends/${id}`);
  }
  save(groupId: string, participantId: string) {
    return this.api.post<FriendSummary>('/friends', { groupId, participantId });
  }
  get(id: string) {
    return this.api.get<FriendDetail>(`/friends/${id}`);
  }
  list() {
    return this.api.get<FriendSummary[]>('/friends');
  }
  // Non-group expenses between the two accounts, in the pair's hidden ledger (G02.2).
  personalExpenses(friendId: string): Observable<ExpenseSummary[]> {
    return this.api.get<ExpenseSummary[]>(`/friends/${friendId}/expenses`);
  }
  personalExpense(friendId: string, expenseId: string): Observable<ExpenseResponse> {
    return this.api.get<ExpenseResponse>(`/friends/${friendId}/expenses/${expenseId}`);
  }
  createPersonalExpense(
    friendId: string,
    request: CreateExpenseRequest,
  ): Observable<ExpenseResponse> {
    return this.api.post<ExpenseResponse>(`/friends/${friendId}/expenses`, request);
  }
  updatePersonalExpense(
    friendId: string,
    expenseId: string,
    request: CreateExpenseRequest,
  ): Observable<ExpenseResponse> {
    return this.api.put<ExpenseResponse>(`/friends/${friendId}/expenses/${expenseId}`, request);
  }
  deletePersonalExpense(
    friendId: string,
    expenseId: string,
    expectedVersion: number,
  ): Observable<void> {
    return this.api.delete<void>(
      `/friends/${friendId}/expenses/${expenseId}?expectedVersion=${expectedVersion}`,
    );
  }
  deletedPersonalExpenses(friendId: string): Observable<DeletedExpense[]> {
    return this.api.get<DeletedExpense[]>(`/friends/${friendId}/expenses/deleted`);
  }
  restorePersonalExpense(
    friendId: string,
    expenseId: string,
    expectedVersion: number,
  ): Observable<ExpenseResponse> {
    return this.api.post<ExpenseResponse>(`/friends/${friendId}/expenses/${expenseId}/restore`, {
      expectedVersion,
    });
  }
}
