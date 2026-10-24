import { Currency } from '../shared/currency';
import { UserSummary } from '../core/auth/auth.models';

export interface PaymentResponse {
  id: string;
  payer: UserSummary;
  payee: UserSummary;
  amountMinor: number;
  currency?: Currency;
  createdAt: string;
}

export interface RecordPaymentRequest {
  operationId: string;
  payerUserId: string;
  payeeUserId: string;
  amountMinor: number;
  currency?: Currency;
}

/**
 * Seeds the settle-up form. Two sources:
 * - `balance`: a balance row was clicked; direction is inferred relative to the current user
 *   from the sign of `netMinor` (used by BalancePanelComponent).
 * - `transaction`: an explicit suggested transfer between two members (from debt simplification);
 *   payer/payee/amount are set directly, no current-user inference.
 */
export type SettlePrefill =
  | { kind: 'balance'; userId: string; netMinor: number }
  | { kind: 'transaction'; payerUserId: string; payeeUserId: string; amountMinor: number };
