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
  /** Non-group personal expenses only; `balances` is the sum of every scope. */
  personalBalances: FriendBalance[];
}

/** One scope's payment in a cross-group settlement (B02); `groupName` is null for Personal. */
export interface SettlementAllocation {
  scopeId: string;
  groupName: string | null;
  payerId: string;
  payeeId: string;
  amountMinor: number;
  paymentId?: string | null;
}
export interface SettlementPreview {
  currency: Currency;
  allocations: SettlementAllocation[];
  skipped: { scopeId: string; groupName: string | null; reason: string }[];
  /** Positive when the friend owes you across the allocations. */
  netMinor: number;
}
export interface SettlementResponse {
  id: string;
  currency: Currency;
  allocations: SettlementAllocation[];
  netMinor: number;
}
