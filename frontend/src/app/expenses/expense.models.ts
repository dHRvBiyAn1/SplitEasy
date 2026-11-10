import { Currency } from '../shared/currency';
import { MoneyInput } from '../shared/currency';
import { ParticipantSummary } from '../groups/participant.models';
import { UserSummary } from '../core/auth/auth.models';
import { ExpenseCategory } from '../dashboard/dashboard.models';

export type SplitType = 'EQUAL' | 'UNEQUAL' | 'PERCENTAGE' | 'WEIGHTS' | 'ADJUSTMENTS';

export interface SplitShare {
  user: ParticipantSummary;
  shareMinor: number;
  currency?: Currency;
}

export interface ExpenseResponse {
  version?: number;
  id: string;
  groupId: string;
  description: string;
  amountMinor: number;
  currency?: Currency;
  splitType: SplitType;
  category: ExpenseCategory;
  spentOn: string;
  paidBy: ParticipantSummary;
  participants: SplitShare[];
  /** Original equal participant set; older responses may omit intent. */
  participantUserIds?: string[] | null;
  /** Original typed inputs, distinct from final rounded shares. */
  splits?: SplitInput[] | null;
  createdAt: string;
}

export interface ExpenseSummary {
  version?: number;
  id: string;
  description: string;
  amountMinor: number;
  currency?: Currency;
  paidBy: ParticipantSummary;
  participantCount: number;
  category: ExpenseCategory;
  spentOn: string;
  /** Viewer's net for this expense: +lent / −borrowed / 0 not-involved. */
  viewerDeltaMinor: number;
  createdAt: string;
}

/** Original minor units (UNEQUAL), basis points (PERCENTAGE), positive integer units (WEIGHTS), or signed adjustment minor units. */
export interface SplitInput {
  userId: string;
  value: number;
}

export type CreateExpenseRequest = MoneyInput & {
  operationId?: string;
  expectedVersion?: number;
  description: string;
  /** Compatibility field names retain immutable financial participant IDs. */
  paidByUserId: string;
  participantUserIds?: string[] | null;
  splitType?: SplitType;
  splits?: SplitInput[] | null;
  category?: ExpenseCategory;
  spentOn?: string;
};

export interface ExpenseRevision {
  action: 'IMPORTED' | 'CREATED' | 'REPLACED' | 'DELETED' | 'RESTORED';
  actor: UserSummary | null;
  recordedAt: string;
  version: number;
  operationId?: string | null;
  before: ExpenseResponse | null;
  after: ExpenseResponse;
}

export interface DeletedExpense {
  expense: ExpenseResponse;
  deletedAt: string;
}
