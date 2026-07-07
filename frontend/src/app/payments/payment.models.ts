import { UserSummary } from '../core/auth/auth.models';

export interface PaymentResponse {
  id: string;
  payer: UserSummary;
  payee: UserSummary;
  amountCents: number;
  createdAt: string;
}

export interface RecordPaymentRequest {
  payerUserId: string;
  payeeUserId: string;
  amountCents: number;
}
