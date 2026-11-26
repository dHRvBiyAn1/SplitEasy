import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Observable, Subscription, takeUntil } from 'rxjs';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';
import { translate } from '../core/i18n/i18n.service';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { DashboardService } from '../dashboard/dashboard.service';
import { HistoryItem, HistoryType } from '../history/history.models';
import { amountToMinor, CURRENCIES, type Currency } from '../shared/currency';
import { MoneyComponent } from '../shared/money.component';
import { SearchFilter, SearchService } from './search.service';

const TYPE_LABEL: Record<HistoryType, string> = {
  get EXPENSE() {
    return translate('shell.expense');
  },
  get PAYMENT() {
    return translate('history.settlement');
  },
  get REFUND() {
    return translate('history.refund');
  },
};

/** Search my accessible expenses, payments and refunds: literal text, dates, per-currency amounts. */
@Component({
  selector: 'app-search',
  imports: [TranslatePipe, RouterLink, MoneyComponent],
  templateUrl: './search.component.html',
  styleUrl: './search.component.scss',
})
export class SearchComponent {
  private readonly service = inject(SearchService);
  private readonly session = inject(SessionService);
  private readonly auth = inject(AuthService);
  private readonly dashboard = inject(DashboardService);
  private readonly destroyRef = inject(DestroyRef);
  private request?: Subscription;
  /** The filter the visible rows were loaded with, so "load more" matches them. */
  private applied: SearchFilter = {};

  // A getter, not a field: a field initializer read this import as undefined in combined spec runs.
  protected get currencies() {
    return CURRENCIES;
  }
  protected readonly groups = () => this.dashboard.data()?.groups ?? [];
  protected readonly items = signal<HistoryItem[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly searched = signal(false);
  protected readonly loading = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly typeLabel = TYPE_LABEL;
  protected readonly currency = signal('');
  protected q = '';
  protected groupId = '';
  protected from = '';
  protected to = '';
  protected minAmount = '';
  protected maxAmount = '';

  constructor() {
    this.dashboard.ensureLoaded();
    this.session.changes.pipe(takeUntilDestroyed()).subscribe(() => {
      this.request?.unsubscribe();
      this.items.set([]);
      this.nextCursor.set(null);
      this.searched.set(false);
      this.loading.set(false);
      this.loadingMore.set(false);
    });
  }

  protected submit() {
    this.formError.set(null);
    if (this.from && this.to && this.from > this.to) {
      this.formError.set(translate('search.badDates'));
      return;
    }
    const currency = this.currency() as Currency | '';
    const min = currency && this.minAmount ? amountToMinor(this.minAmount, currency) : undefined;
    const max = currency && this.maxAmount ? amountToMinor(this.maxAmount, currency) : undefined;
    if (Number.isNaN(min) || Number.isNaN(max) || (min != null && max != null && min > max)) {
      this.formError.set(translate('search.badAmounts'));
      return;
    }
    this.applied = {
      q: this.q.trim(),
      groupId: this.groupId,
      from: this.from,
      to: this.to,
      currency,
      minAmount: min,
      maxAmount: max,
    };
    this.load();
  }

  /** First page, replacing what is shown; a failure leaves a retry, never a half list. */
  protected load() {
    if (!this.auth.isAuthenticated()) return;
    this.request?.unsubscribe();
    this.items.set([]);
    this.nextCursor.set(null);
    this.loadingMore.set(false);
    this.loading.set(true);
    this.error.set(null);
    this.searched.set(true);
    this.request = this.cancel(this.service.page(this.applied)).subscribe({
      next: (page) => {
        this.items.set(page.items);
        this.nextCursor.set(page.nextCursor);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.error.set(translate('search.failed'));
      },
    });
  }

  protected loadMore() {
    const cursor = this.nextCursor();
    if (!cursor || this.loadingMore() || this.loading()) return;
    this.loadingMore.set(true);
    this.error.set(null);
    this.request = this.cancel(this.service.page(this.applied, cursor)).subscribe({
      next: (page) => {
        this.items.update((rows) => [...rows, ...page.items]);
        this.nextCursor.set(page.nextCursor);
        this.loadingMore.set(false);
      },
      error: () => {
        // Loaded rows and the cursor stay, so the same page can be retried.
        this.loadingMore.set(false);
        this.error.set(translate('search.failedMore'));
      },
    });
  }

  private cancel<T>(source: Observable<T>) {
    return source.pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef));
  }
}
