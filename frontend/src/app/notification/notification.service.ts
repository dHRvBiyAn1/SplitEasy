import { Injectable, inject, signal } from '@angular/core';
import { Observable, Subscription, takeUntil, tap } from 'rxjs';
import { ApiService } from '../core/api/api.service';
import { SessionService } from '../core/auth/session.service';
import { ActivityItem } from '../activity/activity.models';

export interface NotificationItem {
  id: string;
  readAt: string | null;
  createdAt: string;
  event: ActivityItem;
}

export interface NotificationPage {
  items: NotificationItem[];
  nextCursor: string | null;
}

export interface NotificationPreferences {
  expenses: boolean;
  payments: boolean;
  groupChanges: boolean;
}

/** Bell count owner: revalidated on navigation/visible focus, cleared with the session. */
@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);
  private pending?: Subscription;
  readonly unread = signal(0);

  constructor() {
    this.session.changes.subscribe(() => {
      this.pending?.unsubscribe();
      this.unread.set(0);
    });
  }

  /** Newest read wins; a failed refresh keeps the last known count. */
  revalidate(): void {
    this.pending?.unsubscribe();
    this.pending = this.api
      .get<{ count: number }>('/notifications/unread-count')
      .pipe(takeUntil(this.session.changes))
      .subscribe({ next: (r) => this.unread.set(r.count), error: () => undefined });
  }

  page(cursor?: string | null): Observable<NotificationPage> {
    return this.api.get<NotificationPage>(`/notifications${cursor ? `?cursor=${cursor}` : ''}`);
  }

  markRead(id: string): Observable<void> {
    return this.api.post<void>(`/notifications/${id}/read`, {}).pipe(tap(() => this.revalidate()));
  }

  markAllRead(): Observable<void> {
    return this.api.post<void>('/notifications/read-all', {}).pipe(
      tap(() => {
        // An older count read may still be in flight and would restore a stale badge.
        this.pending?.unsubscribe();
        this.unread.set(0);
      }),
    );
  }

  preferences(): Observable<NotificationPreferences> {
    return this.api.get<NotificationPreferences>('/me/notification-preferences');
  }

  savePreferences(p: NotificationPreferences): Observable<NotificationPreferences> {
    return this.api.put<NotificationPreferences>('/me/notification-preferences', p);
  }
}
