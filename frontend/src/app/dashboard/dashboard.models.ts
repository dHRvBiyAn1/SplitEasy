import { UserSummary } from '../core/auth/auth.models';

export type GroupType = 'HOME' | 'TRIP' | 'DINING' | 'EVENT' | 'OTHER';

export type ExpenseCategory =
  | 'FOOD_DRINK'
  | 'GROCERIES'
  | 'RENT_HOME'
  | 'UTILITIES'
  | 'TRAVEL'
  | 'TRANSPORT'
  | 'FUN'
  | 'OTHER';

/** Positive netCents = they owe you; negative = you owe them. */
export interface PersonBalance {
  user: UserSummary;
  netCents: number;
}

export interface DashboardGroup {
  id: string;
  name: string;
  type: GroupType;
  memberCount: number;
  totalSpentCents: number;
  youAreOwedCents: number;
  youOweCents: number;
  netCents: number;
}

/** One settle-up row: positive netCents = they owe you (in this group). */
export interface Settlement {
  groupId: string;
  groupName: string;
  counterparty: UserSummary;
  netCents: number;
}

export interface ActivityItem {
  id: string;
  kind: 'EXPENSE' | 'PAYMENT';
  groupId: string;
  groupName: string;
  description: string;
  actor: UserSummary;
  counterparty: UserSummary | null;
  amountCents: number;
  /** EXPENSE: +lent / −borrowed / 0 not-involved. PAYMENT: 0 (settlement, neutral). */
  viewerDeltaCents: number;
  category: ExpenseCategory | null;
  date: string; // ISO date (YYYY-MM-DD)
}

export interface DashboardResponse {
  totalNetCents: number;
  owedCents: number;
  owedPeopleCount: number;
  oweCents: number;
  owePeopleCount: number;
  groupCount: number;
  groups: DashboardGroup[];
  people: PersonBalance[];
  settlements: Settlement[];
  activity: ActivityItem[];
}
