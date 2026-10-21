import { Injectable, inject } from '@angular/core';
import { AuthService } from './auth/auth.service';
import { SessionService } from './auth/session.service';

export type WriteKind = 'group' | 'expense' | 'payment';
export interface PendingWrite<T> {
  owner: string;
  scope: string;
  operationId: string;
  request: T;
  draft?: unknown;
  sessionVersion: number;
}
export class PendingWriteError extends Error {}
const PREFIX = 'spliteasy.pending-write.v1.';
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
      (previous.scope !== scope || JSON.stringify(previous.request) !== JSON.stringify(request))
    ) {
      throw new PendingWriteError(UNCERTAIN_WRITE);
    }
    const pending: PendingWrite<T> = {
      owner,
      scope,
      operationId: previous?.operationId ?? crypto.randomUUID(),
      request,
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
