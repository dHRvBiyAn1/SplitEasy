import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Subscription, takeUntil } from 'rxjs';
import { describeEvent } from '../activity/activity.models';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';
import { NotificationItem, NotificationService } from './notification.service';
import { LocaleDatePipe } from '../core/i18n/locale-date.pipe';
import { TranslatePipe } from '../core/i18n/translate.pipe';

@Component({
  selector: 'app-notifications',
  imports: [TranslatePipe, LocaleDatePipe, RouterLink],
  template: `
    <section class="notes" aria-labelledby="notes-heading">
      <h1 id="notes-heading">{{ 'common.notifications' | t }}</h1>
      <div class="actions">
        <button
          class="dc-btn-outline"
          type="button"
          [disabled]="!service.unread()"
          (click)="markAll()"
        >
          {{ 'notifications.markAllAsRead' | t }}
        </button>
        <a routerLink="/activity">{{ 'notifications.seeAllActivity' | t }}</a>
      </div>
      @if (actionError()) {
        <p role="alert">{{ actionError() }}</p>
      }
      @if (loading()) {
        <p role="status">{{ 'notifications.loadingNotifications' | t }}</p>
      } @else {
        @if (error()) {
          <div role="alert">
            <p>{{ error() }}</p>
            <button class="dc-btn" type="button" (click)="nextCursor() ? loadMore() : load()">
              {{ 'common.tryAgain' | t }}
            </button>
          </div>
        }
        @if (!items().length && !error()) {
          <p>{{ 'notifications.youHaveNoNotifications' | t }}</p>
        }
        <ul class="events">
          @for (n of items(); track n.id) {
            <li [class.unread]="!n.readAt">
              <p>{{ describe(n.event) }}</p>
              <p class="meta">
                <time [attr.datetime]="n.createdAt">{{ n.createdAt | ldate }}</time>
                @if (!n.readAt) {
                  ·
                  <button class="dc-btn-ghost" type="button" (click)="markRead(n)">
                    {{ 'notifications.markAsRead' | t }}
                  </button>
                } @else {
                  {{ 'notifications.read' | t }}
                }
              </p>
            </li>
          }
        </ul>
        @if (nextCursor() && !error()) {
          <button
            class="dc-btn-outline"
            type="button"
            [disabled]="loadingMore()"
            (click)="loadMore()"
          >
            {{ loadingMore() ? 'Loading…' : 'Load more' }}
          </button>
        }
      }
    </section>
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    .notes {
      max-width: 1000px;
      margin: auto;
      padding: var(--sp-7) var(--sp-6) var(--sp-8);
      display: grid;
      gap: var(--sp-5);
      overflow-wrap: anywhere;
    }
    .actions {
      display: flex;
      gap: var(--sp-4);
      align-items: center;
      flex-wrap: wrap;
    }
    .events {
      list-style: none;
      margin: 0;
      padding: 0;
      display: grid;
      gap: var(--sp-4);
    }
    .events li {
      background: var(--surface-raised);
      border-radius: var(--radius-lg);
      padding: var(--sp-4) var(--sp-5);
    }
    .events li.unread {
      font-weight: 600;
    }
    p {
      margin: 0;
    }
    .meta {
      color: var(--ink-soft);
      font-weight: 400;
    }
  `,
})
export class NotificationsComponent {
  protected readonly service = inject(NotificationService);
  private readonly session = inject(SessionService);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);
  private request?: Subscription;

  protected readonly items = signal<NotificationItem[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly describe = describeEvent;

  constructor() {
    this.session.changes.pipe(takeUntilDestroyed()).subscribe(() => {
      this.request?.unsubscribe();
      this.items.set([]);
      this.nextCursor.set(null);
      this.loading.set(false);
      this.loadingMore.set(false);
      this.error.set('Session changed. Try again to load notifications.');
    });
    this.load();
  }

  private readonly guard = <T>(source: import('rxjs').Observable<T>) =>
    source.pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef));

  protected load() {
    if (!this.auth.isAuthenticated()) return;
    this.request?.unsubscribe();
    this.items.set([]);
    this.nextCursor.set(null);
    this.loadingMore.set(false);
    this.loading.set(true);
    this.error.set(null);
    this.request = this.guard(this.service.page()).subscribe({
      next: (page) => {
        this.items.set(page.items);
        this.nextCursor.set(page.nextCursor);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Could not load notifications. Try again.');
      },
    });
  }

  protected loadMore() {
    const cursor = this.nextCursor();
    if (!cursor || this.loadingMore() || this.loading()) return;
    this.loadingMore.set(true);
    this.error.set(null);
    this.request = this.guard(this.service.page(cursor)).subscribe({
      next: (page) => {
        this.items.update((rows) => [...rows, ...page.items]);
        this.nextCursor.set(page.nextCursor);
        this.loadingMore.set(false);
      },
      error: () => {
        this.loadingMore.set(false);
        this.error.set('Could not load more notifications. Try again.');
      },
    });
  }

  protected markRead(n: NotificationItem) {
    this.actionError.set(null);
    this.guard(this.service.markRead(n.id)).subscribe({
      next: () =>
        this.items.update((rows) =>
          rows.map((r) => (r.id === n.id ? { ...r, readAt: new Date().toISOString() } : r)),
        ),
      error: () => this.actionError.set('Could not mark that notification as read. Try again.'),
    });
  }

  protected markAll() {
    this.actionError.set(null);
    this.guard(this.service.markAllRead()).subscribe({
      next: () => {
        const now = new Date().toISOString();
        this.items.update((rows) => rows.map((r) => ({ ...r, readAt: r.readAt ?? now })));
      },
      error: () => this.actionError.set('Could not mark notifications as read. Try again.'),
    });
  }
}
