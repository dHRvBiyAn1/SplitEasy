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

/**
 * Seeds the settle-up form. Two sources:
 * - `balance`: a balance row was clicked; direction is inferred relative to the current user
 *   from the sign of `netCents` (used by BalancePanelComponent).
 * - `transaction`: an explicit suggested transfer between two members (from debt simplification);
 *   payer/payee/amount are set directly, no current-user inference.
 */
export type SettlePrefill =
  | { kind: 'balance'; userId: string; netCents: number }
  | { kind: 'transaction'; payerUserId: string; payeeUserId: string; amountCents: number };
