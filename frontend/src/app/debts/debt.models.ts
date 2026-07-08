import { UserSummary } from '../core/auth/auth.models';

/** A suggested transfer: `from` (a debtor) pays `to` (a creditor) `amountCents`. */
export interface SuggestedTransaction {
  from: UserSummary;
  to: UserSummary;
  amountCents: number;
}

export interface SimplifiedDebtsResponse {
  groupId: string;
  transactions: SuggestedTransaction[];
}
