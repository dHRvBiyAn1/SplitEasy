import { Currency } from '../shared/currency';
import { UserSummary } from '../core/auth/auth.models';

export interface MemberBalance {
  user: UserSummary;
  /** Net position in integer minor units. Positive = owed money; negative = owes money. */
  netMinor: number;
  currency?: Currency;
}

export interface GroupBalancesResponse {
  groupId: string;
  balances: MemberBalance[];
}

/** Directed debt: from owes to. The endpoint defines raw or selected-mode interpretation. */
export interface DirectObligation {
  from: UserSummary;
  to: UserSummary;
  amountMinor: number;
  currency?: Currency;
}
