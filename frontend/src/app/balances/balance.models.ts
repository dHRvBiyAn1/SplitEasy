import { Currency } from '../shared/currency';
import { ParticipantSummary } from '../groups/participant.models';

export interface MemberBalance {
  user: ParticipantSummary;
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
  from: ParticipantSummary;
  to: ParticipantSummary;
  amountMinor: number;
  currency?: Currency;
}
