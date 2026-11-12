import { CreateExpenseRequest } from '../expenses/expense.models';

export type Frequency = 'DAILY' | 'WEEKLY' | 'MONTHLY';
export type ScheduleStatus = 'ACTIVE' | 'NEEDS_REVIEW' | 'CANCELLED';

export interface RecurringSchedule {
  id: string;
  groupId: string;
  createdBy: string;
  /** Each occurrence creates this expense; its date is the occurrence date. */
  template: CreateExpenseRequest;
  frequency: Frequency;
  anchorDate: string;
  timeZone: string;
  nextDate: string;
  version: number;
  status: ScheduleStatus;
  reviewReason: string | null;
  createdAt: string;
  cancelledAt: string | null;
}

export interface ScheduleRequest {
  template: CreateExpenseRequest;
  frequency: Frequency;
  anchorDate: string;
  timeZone: string;
  /** Required when changing future occurrences. */
  expectedVersion?: number;
  /** Create retry key: a replay with the same intent returns the original schedule. */
  operationId?: string;
}
