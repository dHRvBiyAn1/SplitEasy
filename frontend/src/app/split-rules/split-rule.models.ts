import { SplitInput, SplitType } from '../expenses/expense.models';

/** Only ratio splits can be saved; amounts would not survive an amount change. */
export type SavableSplitType = Extract<SplitType, 'EQUAL' | 'PERCENTAGE' | 'WEIGHTS'>;

export interface SplitRule {
  id: string;
  groupId: string;
  name: string;
  splitType: SavableSplitType;
  /** EQUAL: the participant set (value 1); PERCENTAGE: basis points; WEIGHTS: units. */
  inputs: SplitInput[];
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface SplitRuleRequest {
  name: string;
  splitType: SavableSplitType;
  inputs: SplitInput[];
  expectedVersion?: number;
}
