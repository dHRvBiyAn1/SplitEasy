import { Injectable, inject } from '@angular/core';
import { EMPTY, Observable, defer, takeUntil, tap } from 'rxjs';
import { PendingWritesService } from '../core/pending-writes.service';
import { SessionService } from '../core/auth/session.service';
import { ApiService } from '../core/api/api.service';
import { PaymentResponse, RecordPaymentRequest } from './payment.models';

/** Settle-up payments, nested under a group. One service per backend resource (AGENTS.md). */
@Injectable({ providedIn: 'root' })
export class PaymentService {
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);
  private readonly pendingWrites = inject(PendingWritesService);

  listPayments(groupId: string): Observable<PaymentResponse[]> {
    return this.api.get<PaymentResponse[]>(`/groups/${groupId}/payments`);
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
