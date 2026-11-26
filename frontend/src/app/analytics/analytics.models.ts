import { Currency } from '../shared/currency';

export type AnalyticsGroupBy = 'category' | 'month' | 'person';

export interface AnalyticsFilter {
  from?: string;
  to?: string;
  groupId?: string;
  groupBy: AnalyticsGroupBy;
}

/** Refunds are already subtracted, so any value can be negative. */
export interface AnalyticsRow {
  currency: Currency;
  key: string;
  label: string;
  groupTotalMinor: number;
  myShareMinor: number;
  myPaidMinor: number;
}

export interface AnalyticsResponse {
  rows: AnalyticsRow[];
}
