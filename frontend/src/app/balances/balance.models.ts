import { UserSummary } from '../core/auth/auth.models';

export interface MemberBalance {
  user: UserSummary;
  /** Net position in integer cents. Positive = owed money; negative = owes money. */
  netCents: number;
}

export interface GroupBalancesResponse {
  groupId: string;
  balances: MemberBalance[];
}
