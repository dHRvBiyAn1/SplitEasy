import { Currency, minorToDisplay } from '../shared/currency';
import { translate } from '../core/i18n/i18n.service';

export interface ActivityItem {
  id: string;
  seq: number;
  groupId: string;
  /** Null for a personal (friend) ledger. */
  groupName: string | null;
  kind: string;
  subjectId: string | null;
  actor: { id: string; displayName: string } | null;
  summary: { description?: string; name?: string; amountMinor?: number; currency?: string } & {
    member?: { displayName: string };
  };
  occurredAt: string;
}

export interface ActivityPage {
  items: ActivityItem[];
  nextCursor: string | null;
}

/** One readable sentence per event; the actor is the recorder, not necessarily the payer. */
export function describeEvent(e: ActivityItem): string {
  const who = e.actor?.displayName ?? translate('activity.someone');
  const where = e.groupName ?? translate('activity.aPersonalLedger');
  const member = e.summary.member?.displayName ?? translate('activity.aMember');
  const currency = (e.summary.currency ?? 'USD') as Currency;
  const key = `activity.event.${e.kind}`;
  const text = translate(key, {
    who,
    where,
    member,
    description: e.summary.description,
    currency,
    amount: minorToDisplay(e.summary.amountMinor ?? 0, currency),
  });
  if (text !== key) return text;
  return translate('activity.eventOther', { who, action: e.kind.split(/_(.*)/s)[1] ?? '', where });
}
