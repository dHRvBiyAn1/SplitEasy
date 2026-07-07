import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../core/api/api.service';
import { PaymentResponse, RecordPaymentRequest } from './payment.models';

/** Settle-up payments, nested under a group. One service per backend resource (AGENTS.md). */
@Injectable({ providedIn: 'root' })
export class PaymentService {
  private readonly api = inject(ApiService);

  listPayments(groupId: string): Observable<PaymentResponse[]> {
    return this.api.get<PaymentResponse[]>(`/groups/${groupId}/payments`);
  }

  recordPayment(groupId: string, request: RecordPaymentRequest): Observable<PaymentResponse> {
    return this.api.post<PaymentResponse>(`/groups/${groupId}/payments`, request);
  }
}
