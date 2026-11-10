import { Currency } from '../shared/currency';
export interface FriendBalance {
  currency: Currency;
  netMinor: number;
}
export interface FriendSummary {
  id: string;
  displayName: string;
  createdAt: string;
  sharedGroupCount: number;
  hasOpenObligations: boolean;
  balances: FriendBalance[];
}
export interface FriendDetail extends FriendSummary {
  sharedGroups: { id: string; name: string; simplifyDebts: boolean; balances: FriendBalance[] }[];
}
