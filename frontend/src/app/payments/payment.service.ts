import { Injectable, inject } from '@angular/core';
import { EMPTY, Observable, defer, takeUntil, tap } from 'rxjs';
import { PendingWritesService } from '../core/pending-writes.service';
import { SessionService } from '../core/auth/session.service';
import { ApiService } from '../core/api/api.service';
import {
  CorrectPaymentRequest,
  DeletedPayment,
  LedgerRevision,
  PaymentResponse,
  RecordPaymentRequest,
  RecordRefundRequest,
  RefundResponse,
} from './payment.models';

/** Settle-up payments, nested under a group. One service per backend resource (AGENTS.md). */
@Injectable({ providedIn: 'root' })
export class PaymentService {
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);
  private readonly pendingWrites = inject(PendingWritesService);

  listPayments(groupId: string): Observable<PaymentResponse[]> {
    return this.api.get<PaymentResponse[]>(`/groups/${groupId}/payments`);
  }

  listDeleted(groupId: string): Observable<DeletedPayment[]> {
    return this.api.get<DeletedPayment[]>(`/groups/${groupId}/payments/deleted`);
  }

  history(groupId: string, paymentId: string): Observable<LedgerRevision<PaymentResponse>[]> {
    return this.api.get<LedgerRevision<PaymentResponse>[]>(
      `/groups/${groupId}/payments/${paymentId}/history`,
    );
  }

  /** A correction states the version it saw; the server rejects a stale edit before mutating. */
  correctPayment(
    groupId: string,
    paymentId: string,
    request: CorrectPaymentRequest,
  ): Observable<PaymentResponse> {
    return this.api
      .put<PaymentResponse>(`/groups/${groupId}/payments/${paymentId}`, request)
      .pipe(takeUntil(this.session.changes));
  }

  deletePayment(groupId: string, paymentId: string, expectedVersion: number): Observable<void> {
    return this.api
      .delete<void>(`/groups/${groupId}/payments/${paymentId}?expectedVersion=${expectedVersion}`)
      .pipe(takeUntil(this.session.changes));
  }

  restorePayment(
    groupId: string,
    paymentId: string,
    expectedVersion: number,
  ): Observable<PaymentResponse> {
    return this.api
      .post<PaymentResponse>(`/groups/${groupId}/payments/${paymentId}/restore`, {
        expectedVersion,
      })
      .pipe(takeUntil(this.session.changes));
  }

  listRefunds(groupId: string, expenseId: string): Observable<RefundResponse[]> {
    return this.api.get<RefundResponse[]>(`/groups/${groupId}/expenses/${expenseId}/refunds`);
  }

  recordRefund(
    groupId: string,
    expenseId: string,
    request: RecordRefundRequest,
    draft?: unknown,
  ): Observable<RefundResponse> {
    const version = this.session.version;
    return defer(() => {
      if (this.session.version !== version) return EMPTY;
      const pending = this.pendingWrites.prepare('refund', expenseId, request, draft);
      return this.api
        .post<RefundResponse>(`/groups/${groupId}/expenses/${expenseId}/refunds`, {
          ...request,
          operationId: pending.operationId,
        })
        .pipe(tap(() => this.pendingWrites.complete('refund', pending)));
    }).pipe(takeUntil(this.session.changes));
  }

  deleteRefund(
    groupId: string,
    expenseId: string,
    refundId: string,
    expectedVersion: number,
  ): Observable<void> {
    return this.api
      .delete<void>(
        `/groups/${groupId}/expenses/${expenseId}/refunds/${refundId}?expectedVersion=${expectedVersion}`,
      )
      .pipe(takeUntil(this.session.changes));
  }

  recordPayment(
    groupId: string,
    request: Omit<RecordPaymentRequest, 'operationId'>,
    draft?: unknown,
  ): Observable<PaymentResponse> {
    const version = this.session.version;
    return defer(() => {
      if (this.session.version !== version) return EMPTY;
      const intent = { ...request, currency: request.currency ?? ('USD' as const) };
      const pending = this.pendingWrites.prepare('payment', groupId, intent, draft);
      return this.api
        .post<PaymentResponse>(`/groups/${groupId}/payments`, {
          ...intent,
          operationId: pending.operationId,
        })
        .pipe(tap(() => this.pendingWrites.complete('payment', pending)));
    }).pipe(takeUntil(this.session.changes));
  }
}
