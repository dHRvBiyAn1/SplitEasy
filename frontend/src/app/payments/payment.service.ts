import { Injectable, inject } from '@angular/core';
import { EMPTY, Observable, defer, takeUntil, tap } from 'rxjs';
import { SessionService } from '../core/auth/session.service';
import { ApiService } from '../core/api/api.service';
import { PaymentResponse, RecordPaymentRequest } from './payment.models';

/** Settle-up payments, nested under a group. One service per backend resource (AGENTS.md). */
@Injectable({ providedIn: 'root' })
export class PaymentService {
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);
  private pending: { groupId: string; request: RecordPaymentRequest } | null = null;

  constructor() {
    this.session.changes.subscribe(() => (this.pending = null));
  }

  listPayments(groupId: string): Observable<PaymentResponse[]> {
    return this.api.get<PaymentResponse[]>(`/groups/${groupId}/payments`);
  }

  recordPayment(
    groupId: string,
    request: Omit<RecordPaymentRequest, 'operationId'>,
  ): Observable<PaymentResponse> {
    const previous = this.pending;
    if (
      !previous ||
      previous.groupId !== groupId ||
      previous.request.payerUserId !== request.payerUserId ||
      previous.request.payeeUserId !== request.payeeUserId ||
      previous.request.amountCents !== request.amountCents
    ) {
      this.pending = { groupId, request: { ...request, operationId: crypto.randomUUID() } };
    }
    // Preserve the operation across uncertain responses and modal reopen, until intent changes.
    const pending = this.pending!;
    const version = this.session.version;
    return defer(() =>
      this.session.version === version
        ? this.api.post<PaymentResponse>(`/groups/${groupId}/payments`, pending.request)
        : EMPTY,
    ).pipe(
      takeUntil(this.session.changes),
      tap(() => {
        if (this.pending === pending) this.pending = null;
      }),
    );
  }
}
