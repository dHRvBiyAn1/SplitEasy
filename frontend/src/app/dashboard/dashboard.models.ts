import { Currency } from '../shared/currency';
import { UserSummary } from '../core/auth/auth.models';

export type GroupType = 'HOME' | 'TRIP' | 'DINING' | 'EVENT' | 'OTHER';

export type ExpenseCategory =
  'FOOD_DRINK' | 'GROCERIES' | 'RENT_HOME' | 'UTILITIES' | 'TRAVEL' | 'TRANSPORT' | 'FUN' | 'OTHER';

/** Positive netMinor = they owe you; negative = you owe them. */
export interface PersonBalance {
  user: UserSummary;
  netMinor: number;
  currency?: Currency;
}

export interface DashboardGroup {
  totals?: GroupCurrencyTotals[];
  id: string;
  name: string;
  type: GroupType;
  memberCount: number;
  totalSpentMinor: number | null;
  youAreOwedMinor: number | null;
  youOweMinor: number | null;
  netMinor: number | null;
  currency?: Currency;
  /** Group setting: settle-up uses suggested netted transfers for this group. */
  simplifyDebts: boolean;
}

/** One settle-up row: positive netMinor = they owe you (in this group). */
export interface Settlement {
  groupId: string;
  groupName: string;
  counterparty: UserSummary;
  netMinor: number;
  currency?: Currency;
}

export interface ActivityItem {
  id: string;
  kind: 'EXPENSE' | 'PAYMENT';
  groupId: string;
  groupName: string;
  description: string;
  actor: UserSummary;
  counterparty: UserSummary | null;
  amountMinor: number;
  currency?: Currency;
  /** EXPENSE: +lent / −borrowed / 0 not-involved. PAYMENT: 0 (settlement, neutral). */
  viewerDeltaMinor: number;
  category: ExpenseCategory | null;
  date: string; // ISO date (YYYY-MM-DD)
}

export interface DashboardResponse {
  totals?: CurrencyTotals[];
  totalNetMinor: number | null;
  owedMinor: number | null;
  owedPeopleCount: number;
  oweMinor: number | null;
  owePeopleCount: number;
  groupCount: number;
  groups: DashboardGroup[];
  people: PersonBalance[];
  settlements: Settlement[];
  activity: ActivityItem[];
}

export interface CurrencyTotals {
  currency: Currency;
  totalNetMinor: number;
  owedMinor: number;
  owedPeopleCount: number;
  oweMinor: number;
  owePeopleCount: number;
}
export interface GroupCurrencyTotals {
  currency: Currency;
  totalSpentMinor: number;
  youAreOwedMinor: number;
  youOweMinor: number;
  netMinor: number;
}
/** Transitional scalar values represent USD only; new responses always carry authoritative rows. */
export function dashboardTotals(d: DashboardResponse): CurrencyTotals[] {
  return (
    d.totals ?? [
      {
        currency: 'USD',
        totalNetMinor: d.totalNetMinor ?? 0,
        owedMinor: d.owedMinor ?? 0,
        owedPeopleCount: d.owedPeopleCount,
        oweMinor: d.oweMinor ?? 0,
        owePeopleCount: d.owePeopleCount,
      },
    ]
  );
}
export function groupTotals(g: DashboardGroup): GroupCurrencyTotals[] {
  return (
    g.totals ?? [
      {
        currency: 'USD',
        totalSpentMinor: g.totalSpentMinor ?? 0,
        youAreOwedMinor: g.youAreOwedMinor ?? 0,
        youOweMinor: g.youOweMinor ?? 0,
        netMinor: g.netMinor ?? 0,
      },
    ]
  );
}
