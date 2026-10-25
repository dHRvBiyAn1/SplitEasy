import { Currency } from '../shared/currency';
import { UserSummary } from '../core/auth/auth.models';

/** A suggested transfer: `from` (a debtor) pays `to` (a creditor) `amountMinor`. */
export interface SuggestedTransaction {
  from: UserSummary;
  to: UserSummary;
  amountMinor: number;
  currency?: Currency;
}

export interface SimplifiedDebtsResponse {
  groupId: string;
  transactions: SuggestedTransaction[];
  warning?: string | null;
}
