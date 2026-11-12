import { Currency, minorToDisplay } from '../shared/currency';

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

const VERB: Record<string, string> = {
  CREATED: 'added',
  REPLACED: 'edited',
  CORRECTED: 'corrected',
  DELETED: 'deleted',
  RESTORED: 'restored',
};

/** One readable sentence per event; the actor is the recorder, not necessarily the payer. */
export function describeEvent(e: ActivityItem): string {
  const who = e.actor?.displayName ?? 'Someone';
  const [family, action = ''] = e.kind.split(/_(.*)/s);
  const where = e.groupName ?? 'a personal ledger';
  const verb = VERB[action];
  if (family === 'EXPENSE') return `${who} ${verb} expense “${e.summary.description}”`;
  if (family === 'PAYMENT') return `${who} ${verb} a settlement in ${where}`;
  if (family === 'REFUND') return `${who} ${verb} a refund in ${where}`;
  const member = e.summary.member?.displayName ?? 'a member';
  switch (e.kind) {
    case 'MEMBER_ADDED':
      return `${who} added ${member} to ${where}`;
    case 'MEMBER_REMOVED':
      return `${who} removed ${member} from ${where}`;
    case 'BALANCE_REMINDER': {
      const currency = (e.summary.currency ?? 'USD') as Currency;
      return `${who} reminded you about ${currency} ${minorToDisplay(e.summary.amountMinor ?? 0, currency)} in ${where}`;
    }
    case 'MEMBER_LEFT':
      return `${who} left ${where}`;
    default:
      return `${who} ${action.toLowerCase()} ${where}`;
  }
}
