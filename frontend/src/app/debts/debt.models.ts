import { Currency } from '../shared/currency';
import { ParticipantSummary } from '../groups/participant.models';

/** A suggested transfer: `from` (a debtor) pays `to` (a creditor) `amountMinor`. */
export interface SuggestedTransaction {
  from: ParticipantSummary;
  to: ParticipantSummary;
  amountMinor: number;
  currency?: Currency;
}

export interface SimplifiedDebtsResponse {
  groupId: string;
  transactions: SuggestedTransaction[];
  warning?: string | null;
}
