import { Currency } from '../shared/currency';
import { ParticipantSummary } from '../groups/participant.models';

export interface PaymentResponse {
  id: string;
  payer: ParticipantSummary;
  payee: ParticipantSummary;
  amountMinor: number;
  currency?: Currency;
  createdAt: string;
}

export interface RecordPaymentRequest {
  operationId: string;
  /** Compatibility field names; values are immutable financial participant IDs. */
  payerUserId: string;
  payeeUserId: string;
  amountMinor: number;
  currency?: Currency;
}
