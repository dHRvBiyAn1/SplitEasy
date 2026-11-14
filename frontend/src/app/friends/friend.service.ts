import { Injectable, inject } from '@angular/core';
import { EMPTY, Observable, defer, takeUntil, tap } from 'rxjs';
import { PendingWritesService } from '../core/pending-writes.service';
import { SessionService } from '../core/auth/session.service';
import {
  DeletedPayment,
  LedgerRevision,
  PaymentResponse,
  RecordPaymentRequest,
} from '../payments/payment.models';
import { ApiService } from '../core/api/api.service';
import {
  CreateExpenseRequest,
  DeletedExpense,
  ExpenseResponse,
  ExpenseSummary,
} from '../expenses/expense.models';
import {
  FriendDetail,
  FriendSummary,
  SettlementAllocation,
  SettlementPreview,
  SettlementResponse,
} from './friend.models';
@Injectable({ providedIn: 'root' })
export class FriendService {
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);
  private readonly pendingWrites = inject(PendingWritesService);
  // Settle everything in one currency across every shared scope, atomically (B02).
  settlementPreview(friendId: string, currency: string) {
    return this.api.get<SettlementPreview>(
      `/friends/${friendId}/settlement-preview?currency=${currency}`,
    );
  }
  settleAll(
    friendId: string,
    currency: string,
    allocations: SettlementAllocation[],
    operationId: string,
  ) {
    return this.api.post<SettlementResponse>(`/friends/${friendId}/settlements`, {
      currency,
      allocations,
      operationId,
    });
  }
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

  // Settlements between the pair, in the same hidden ledger (G02.3).
  personalPayments(friendId: string): Observable<PaymentResponse[]> {
    return this.api.get<PaymentResponse[]>(`/friends/${friendId}/payments`);
  }

  deletedPersonalPayments(friendId: string): Observable<DeletedPayment[]> {
    return this.api.get<DeletedPayment[]>(`/friends/${friendId}/payments/deleted`);
  }

  personalPaymentHistory(
    friendId: string,
    paymentId: string,
  ): Observable<LedgerRevision<PaymentResponse>[]> {
    return this.api.get<LedgerRevision<PaymentResponse>[]>(
      `/friends/${friendId}/payments/${paymentId}/history`,
    );
  }

  deletePersonalPayment(
    friendId: string,
    paymentId: string,
    expectedVersion: number,
  ): Observable<void> {
    return this.api.delete<void>(
      `/friends/${friendId}/payments/${paymentId}?expectedVersion=${expectedVersion}`,
    );
  }

  restorePersonalPayment(
    friendId: string,
    paymentId: string,
    expectedVersion: number,
  ): Observable<PaymentResponse> {
    return this.api.post<PaymentResponse>(`/friends/${friendId}/payments/${paymentId}/restore`, {
      expectedVersion,
    });
  }

  /** Keyed like a group payment; the pending-write scope is `friend:<friendId>`. */
  recordPersonalPayment(
    friendId: string,
    request: Omit<RecordPaymentRequest, 'operationId'>,
    draft?: unknown,
  ): Observable<PaymentResponse> {
    const version = this.session.version;
    return defer(() => {
      if (this.session.version !== version) return EMPTY;
      const intent = { ...request, currency: request.currency ?? ('USD' as const) };
      const pending = this.pendingWrites.prepare('payment', `friend:${friendId}`, intent, draft);
      return this.api
        .post<PaymentResponse>(`/friends/${friendId}/payments`, {
          ...intent,
          operationId: pending.operationId,
        })
        .pipe(tap(() => this.pendingWrites.complete('payment', pending)));
    }).pipe(takeUntil(this.session.changes));
  }
}
