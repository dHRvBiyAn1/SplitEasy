import { UserSummary } from '../core/auth/auth.models';

export type SplitType = 'EQUAL' | 'UNEQUAL' | 'PERCENTAGE';

export interface SplitShare {
  user: UserSummary;
  shareCents: number;
}

export interface ExpenseResponse {
  id: string;
  groupId: string;
  description: string;
  amountCents: number;
  splitType: SplitType;
  paidBy: UserSummary;
  participants: SplitShare[];
  createdAt: string;
}

export interface ExpenseSummary {
  id: string;
  description: string;
  amountCents: number;
  paidBy: UserSummary;
  participantCount: number;
  createdAt: string;
}

/** value = cents (UNEQUAL) or basis points (PERCENTAGE). */
export interface SplitInput {
  userId: string;
  value: number;
}

export interface CreateExpenseRequest {
  description: string;
  amountCents: number;
  paidByUserId: string;
  participantUserIds: string[] | null;
  splitType: SplitType;
  splits: SplitInput[] | null;
}
