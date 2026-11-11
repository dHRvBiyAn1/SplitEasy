import { Currency } from '../shared/currency';
import { ParticipantSummary } from '../groups/participant.models';
import { UserSummary } from '../core/auth/auth.models';

/** How the people involved moved the money themselves. SplitEasy records it; it moves nothing. */
export type PaymentMethod = 'CASH' | 'BANK_TRANSFER' | 'CARD' | 'ONLINE' | 'OTHER';

export const PAYMENT_METHOD_LABEL: Record<PaymentMethod, string> = {
  CASH: 'Cash',
  BANK_TRANSFER: 'Bank transfer',
  CARD: 'Card',
  ONLINE: 'Online',
  OTHER: 'Other',
};

export interface PaymentResponse {
  id: string;
  payer: ParticipantSummary;
  payee: ParticipantSummary;
  amountMinor: number;
  currency?: Currency;
  createdAt: string;
  paidOn: string;
  method: PaymentMethod;
  note: string | null;
  recordedBy: UserSummary | null;
  version: number;
  deletedAt: string | null;
}

export interface DeletedPayment {
  payment: PaymentResponse;
  deletedAt: string;
}

export interface LedgerRevision<T> {
  action: 'IMPORTED' | 'CREATED' | 'CORRECTED' | 'DELETED' | 'RESTORED';
  actor: UserSummary | null;
  recordedAt: string;
  version: number;
  operationId: string | null;
  before: T | null;
  after: T;
}

export interface RecordPaymentRequest {
  operationId: string;
  /** Compatibility field names; values are immutable financial participant IDs. */
  payerUserId: string;
  payeeUserId: string;
  amountMinor: number;
  currency?: Currency;
  paidOn?: string;
  method?: PaymentMethod;
  note?: string | null;
}

/** A full replacement of a payment's correctable facts, at the version the editor saw. */
export interface CorrectPaymentRequest {
  payerUserId: string;
  payeeUserId: string;
  amountMinor: number;
  paidOn: string;
  method: PaymentMethod;
  note: string | null;
  expectedVersion: number;
}

export interface RefundShare {
  user: ParticipantSummary;
  shareMinor: number;
  currency: Currency;
}

export interface RefundResponse {
  id: string;
  expenseId: string;
  amountMinor: number;
  currency: Currency;
  receivedBy: ParticipantSummary;
  refundedOn: string;
  note: string | null;
  recordedBy: UserSummary | null;
  createdAt: string;
  version: number;
  deletedAt: string | null;
  participants: RefundShare[];
}

export interface RecordRefundRequest {
  amountMinor: number;
  currency: Currency;
  refundedOn: string;
  note?: string | null;
}
