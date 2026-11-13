import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Subscription, takeUntil } from 'rxjs';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';
import { DashboardService } from '../dashboard/dashboard.service';
import { ActivityItem, describeEvent } from './activity.models';
import { ActivityService } from './activity.service';
import { LocaleDatePipe } from '../core/i18n/locale-date.pipe';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { translate } from '../core/i18n/i18n.service';

/** Account activity, newest first, a page at a time, with an optional group filter. */
@Component({
  selector: 'app-activity',
  imports: [TranslatePipe, LocaleDatePipe, RouterLink],
  template: `
    <section class="activity" aria-labelledby="activity-heading">
      <h1 id="activity-heading">{{ 'common.activity' | t }}</h1>
      <label class="filter"
        >{{ 'common.group' | t }}
        <select name="group" [value]="groupId()" (change)="filter($any($event.target).value)">
          <option value="">{{ 'activity.allGroups' | t }}</option>
          @for (g of groups(); track g.id) {
            <option [value]="g.id">{{ g.name }}</option>
          }
        </select>
      </label>
      @if (loading()) {
        <p role="status">{{ 'activity.loadingActivity' | t }}</p>
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
          <p>{{ 'activity.noActivityYet' | t }}</p>
        }
        <ul class="events">
          @for (item of items(); track item.id) {
            <li>
              <p>{{ describe(item) }}</p>
              <p class="meta">
                <time [attr.datetime]="item.occurredAt">{{ item.occurredAt | ldate }}</time>
                @if (item.groupName) {
                  · <a [routerLink]="['/groups', item.groupId]">{{ item.groupName }}</a>
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
        } @else if (!nextCursor() && items().length && !error()) {
          <p class="meta">
            {{ 'activity.thatIsEverythingRecorded' | t }}
          </p>
        }
      }
    </section>
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    .activity {
      max-width: 1000px;
      margin: auto;
      padding: var(--sp-7) var(--sp-6) var(--sp-8);
      display: grid;
      gap: var(--sp-5);
      overflow-wrap: anywhere;
    }
    .filter {
      display: grid;
      gap: var(--sp-1);
      max-width: 20rem;
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
    p {
      margin: 0;
    }
    .meta {
      color: var(--ink-soft);
    }
  `,
})
export class ActivityComponent {
  private readonly service = inject(ActivityService);
  private readonly session = inject(SessionService);
  private readonly auth = inject(AuthService);
  private readonly dashboard = inject(DashboardService);
  private readonly destroyRef = inject(DestroyRef);
  private request?: Subscription;

  protected readonly groups = () => this.dashboard.data()?.groups ?? [];
  protected readonly groupId = signal('');
  protected readonly items = signal<ActivityItem[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly describe = describeEvent;

  constructor() {
    this.session.changes.pipe(takeUntilDestroyed()).subscribe(() => {
      this.request?.unsubscribe();
      this.items.set([]);
      this.nextCursor.set(null);
      this.loading.set(false);
      this.loadingMore.set(false);
      this.error.set(translate('activity.sessionChangedTryAgain'));
    });
    this.dashboard.ensureLoaded();
    this.load();
  }

  protected filter(groupId: string) {
    this.groupId.set(groupId);
    this.load();
  }

  protected load() {
    if (!this.auth.isAuthenticated()) return;
    this.request?.unsubscribe();
    this.items.set([]);
    this.nextCursor.set(null);
    this.loadingMore.set(false);
    this.loading.set(true);
    this.error.set(null);
    this.request = this.service
      .page(this.groupId())
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.items.set(page.items);
          this.nextCursor.set(page.nextCursor);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.error.set(translate('activity.couldNotLoadActivity'));
        },
      });
  }

  protected loadMore() {
    const cursor = this.nextCursor();
    if (!cursor || this.loadingMore() || this.loading()) return;
    this.loadingMore.set(true);
    this.error.set(null);
    this.request = this.service
      .page(this.groupId(), cursor)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.items.update((rows) => [...rows, ...page.items]);
          this.nextCursor.set(page.nextCursor);
          this.loadingMore.set(false);
        },
        error: () => {
          // Loaded rows and the cursor stay so the same page can be retried.
          this.loadingMore.set(false);
          this.error.set(translate('activity.couldNotLoadMore'));
        },
      });
  }
}
