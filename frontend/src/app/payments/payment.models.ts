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

/** Prefill emitted when a balance row's "Settle up" is clicked, seeding the settle-up form. */
export interface SettlePrefill {
  userId: string;
  netCents: number;
}
