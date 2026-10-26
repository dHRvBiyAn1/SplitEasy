import { Currency, CURRENCIES, MAX_TRANSACTION_MINOR } from '../shared/currency';
import { Injectable, inject } from '@angular/core';
import { AuthService } from './auth/auth.service';
import { SessionService } from './auth/session.service';

export type WriteKind = 'group' | 'expense' | 'payment';
export interface PendingWrite<T> {
  owner: string;
  scope: string;
  operationId: string;
  request: T;
  /** Absent means the original account-only financial schema; its IDs remain immutable. */
  identityVersion?: 1;
  draft?: unknown;
  sessionVersion: number;
}
export class PendingWriteError extends Error {}
const PREFIX = 'spliteasy.pending-write.v1.';
/** Object property order is not changed financial intent; retain every key and array position. */
function intentJson(value: unknown): string {
  return JSON.stringify(value, (_key, item) =>
    item && typeof item === 'object' && !Array.isArray(item)
      ? Object.fromEntries(Object.entries(item).sort(([a], [b]) => a.localeCompare(b)))
      : item,
  );
}
export const UNCERTAIN_WRITE =
  'This request may already have been saved. Retry the original draft, or check history before starting a separate entry.';

/** Reload durable, tab isolated retry identities. Never dispatches or automatically retries. */
@Injectable({ providedIn: 'root' })
export class PendingWritesService {
  private readonly auth = inject(AuthService);
  private readonly session = inject(SessionService);

  constructor() {
    this.session.changes.subscribe(() => {
      try {
        for (const key of Object.keys(sessionStorage))
          if (key.startsWith(PREFIX)) sessionStorage.removeItem(key);
      } catch {
        /* Storage failure blocks subsequent writes in prepare(). */
      }
    });
  }

  read<T>(kind: WriteKind): PendingWrite<T> | null {
    const owner = this.auth.user()?.id;
    if (!owner) return null;
    const raw = sessionStorage.getItem(PREFIX + owner + '.' + kind);
    if (!raw) return null;
    const stored = JSON.parse(raw) as PendingWrite<T>;
    if (stored.owner !== owner || !stored.operationId || !stored.scope || !stored.request) {
      throw new PendingWriteError(
        'The saved retry draft is unreadable. Check history before starting a separate entry.',
      );
    }
    if (kind !== 'group') {
      if (stored.identityVersion !== undefined && stored.identityVersion !== 1)
        throw new PendingWriteError(
          'Upgrade the app to read this saved identity version. Check history before starting a separate entry.',
        );
      const request = { ...stored.request } as Record<string, unknown>;
      const ids =
        kind === 'payment'
          ? [request['payerUserId'], request['payeeUserId']]
          : [request['paidByUserId']];
      if (
        ids.some((id) => typeof id !== 'string' || !id) ||
        (kind === 'expense' &&
          request['participantUserIds'] != null &&
          (!Array.isArray(request['participantUserIds']) ||
            request['participantUserIds'].some((id: unknown) => typeof id !== 'string' || !id))) ||
        (kind === 'expense' &&
          request['splits'] != null &&
          (!Array.isArray(request['splits']) ||
            request['splits'].some(
              (row: { userId?: unknown } | null) =>
                !row || typeof row.userId !== 'string' || !row.userId,
            )))
      )
        throw new PendingWriteError(
          'Upgrade or check history: the saved financial identities cannot be mapped safely. The original draft has not been changed.',
        );
      const currency = request['currency'] ?? 'USD';
      const legacy = request['amountCents'];
      if (
        !CURRENCIES.includes(currency as Currency) ||
        (legacy != null && (currency !== 'USD' || request['amountMinor'] != null))
      )
        throw new PendingWriteError(
          'The saved currency is unreadable. Check history before starting a separate entry.',
        );
      const amount = request['amountMinor'] ?? legacy;
      if (
        typeof amount !== 'number' ||
        !Number.isSafeInteger(amount) ||
        amount <= 0 ||
        amount > MAX_TRANSACTION_MINOR
      )
        throw new PendingWriteError(
          'The saved amount is unreadable. Check history before starting a separate entry.',
        );
      // A pre-C01 uncertain draft is USD regardless of the account's later preference.
      request['currency'] = currency;
      request['amountMinor'] = amount;
      delete request['amountCents'];
      stored.request = request as T;
      if (kind === 'payment' && stored.draft && typeof stored.draft === 'object') {
        const draft = { ...stored.draft } as Record<string, unknown>;
        if (draft['netMinor'] == null && draft['netCents'] != null) {
          if (currency !== 'USD')
            throw new PendingWriteError('The saved currency is unreadable. Check payment history.');
          draft['netMinor'] = draft['netCents'];
          delete draft['netCents'];
        }
        if (draft['currency'] != null && draft['currency'] !== currency)
          throw new PendingWriteError('The saved currencies disagree. Check payment history.');
        draft['currency'] = currency;
        stored.draft = draft;
      }
    }
    return { ...stored, sessionVersion: this.session.version };
  }

  prepare<T>(kind: WriteKind, scope: string, request: T, draft?: unknown): PendingWrite<T> {
    const owner = this.auth.user()?.id;
    if (!owner) throw new PendingWriteError('Sign in again before saving.');
    let previous: PendingWrite<T> | null;
    try {
      previous = this.read<T>(kind);
    } catch {
      throw new PendingWriteError(
        'Cannot read the saved retry draft. Check history before starting a separate entry.',
      );
    }
    if (
      previous &&
      (previous.scope !== scope || intentJson(previous.request) !== intentJson(request))
    ) {
      throw new PendingWriteError(UNCERTAIN_WRITE);
    }
    const pending: PendingWrite<T> = {
      owner,
      scope,
      operationId: previous?.operationId ?? crypto.randomUUID(),
      request,
      ...(previous
        ? previous.identityVersion
          ? { identityVersion: previous.identityVersion }
          : {}
        : kind !== 'group'
          ? { identityVersion: 1 as const }
          : {}),
      draft,
      sessionVersion: this.session.version,
    };
    try {
      sessionStorage.setItem(PREFIX + owner + '.' + kind, JSON.stringify(pending));
    } catch {
      throw new PendingWriteError(
        'Cannot safely save the retry identity in browser storage. Enable browser storage and try again.',
      );
    }
    return pending;
  }

  complete<T>(kind: WriteKind, pending: PendingWrite<T>): void {
    if (pending.owner !== this.auth.user()?.id || pending.sessionVersion !== this.session.version)
      return;
    try {
      if (this.read<T>(kind)?.operationId === pending.operationId) this.abandon(kind);
    } catch {
      /* Keep the original safe retry identity if storage becomes unavailable after success. */
    }
  }

  abandon(kind: WriteKind): void {
    const owner = this.auth.user()?.id;
    if (owner) sessionStorage.removeItem(PREFIX + owner + '.' + kind);
  }
}
