import { UserSummary } from '../core/auth/auth.models';

export interface SplitShare {
  user: UserSummary;
  shareCents: number;
}

export interface ExpenseResponse {
  id: string;
  groupId: string;
  description: string;
  amountCents: number;
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

export interface CreateExpenseRequest {
  description: string;
  amountCents: number;
  paidByUserId: string;
  participantUserIds: string[] | null;
}
