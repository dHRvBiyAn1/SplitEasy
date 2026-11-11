import { Currency } from '../shared/currency';

export type HistoryType = 'EXPENSE' | 'PAYMENT' | 'REFUND';

export interface HistoryItem {
  type: HistoryType;
  id: string;
  groupId: string;
  groupName: string | null;
  financialDate: string;
  description: string;
  currency: Currency;
  amountMinor: number;
  updatedAt: string;
}

export interface HistoryPage {
  items: HistoryItem[];
  nextCursor: string | null;
}

/** Inclusive financial-date bounds; blank means open-ended. */
export interface HistoryFilter {
  from?: string;
  to?: string;
}
