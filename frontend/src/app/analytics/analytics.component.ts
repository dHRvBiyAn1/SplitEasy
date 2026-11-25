import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription, takeUntil } from 'rxjs';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { translate } from '../core/i18n/i18n.service';
import { Currency } from '../shared/currency';
import { MoneyComponent } from '../shared/money.component';
import { AnalyticsGroupBy, AnalyticsRow } from './analytics.models';
import { AnalyticsService } from './analytics.service';

interface CurrencySection {
  currency: Currency;
  rows: (AnalyticsRow & { percent: number })[];
}

const CATEGORY_LABEL: Record<string, string> = {
  FOOD_DRINK: 'newExpenseModal.foodDrink',
  GROCERIES: 'newExpenseModal.groceries',
  RENT_HOME: 'newExpenseModal.rentHome',
  UTILITIES: 'newExpenseModal.utilities',
  TRAVEL: 'newExpenseModal.travel',
  TRANSPORT: 'newExpenseModal.transport',
  FUN: 'newExpenseModal.fun',
  OTHER: 'groupSettings.other',
};

/** Spending by category, month or payer, one section per currency (never added together). */
@Component({
  selector: 'app-analytics',
  imports: [TranslatePipe, RouterLink, MoneyComponent],
  templateUrl: './analytics.component.html',
  styleUrl: './analytics.component.scss',
})
export class AnalyticsComponent {
  private readonly service = inject(AnalyticsService);
  private readonly session = inject(SessionService);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private request?: Subscription;

  protected groupId = '';
  protected from = '';
  protected to = '';
  protected groupBy: AnalyticsGroupBy = 'category';
  /** The grouping the visible table was loaded with, so headings match the data. */
  protected readonly shownBy = signal<AnalyticsGroupBy>('category');
  protected readonly sections = signal<CurrencySection[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.session.changes.pipe(takeUntilDestroyed()).subscribe(() => {
      this.request?.unsubscribe();
      this.sections.set([]);
      this.loading.set(false);
      this.error.set(translate('analytics.loadFailed'));
    });
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.groupId = params.get('groupId') ?? '';
      this.load();
    });
  }

  protected byLabel(by: AnalyticsGroupBy): string {
    return translate(
      by === 'category'
        ? 'analytics.byCategory'
        : by === 'month'
          ? 'analytics.byMonth'
          : 'analytics.byPerson',
    );
  }

  protected rowLabel(row: AnalyticsRow): string {
    return this.shownBy() === 'category' && CATEGORY_LABEL[row.key]
      ? translate(CATEGORY_LABEL[row.key])
      : row.label;
  }

  protected load() {
    if (!this.auth.isAuthenticated()) return;
    if (this.from && this.to && this.from > this.to) {
      this.error.set(translate('history.theStartDateMust'));
      return;
    }
    this.request?.unsubscribe();
    this.loading.set(true);
    this.error.set(null);
    const by = this.groupBy;
    this.request = this.service
      .read({
        groupBy: by,
        groupId: this.groupId || undefined,
        from: this.from || undefined,
        to: this.to || undefined,
      })
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ rows }) => {
          const sections = new Map<Currency, AnalyticsRow[]>();
          for (const r of rows) sections.set(r.currency, [...(sections.get(r.currency) ?? []), r]);
          this.sections.set(
            [...sections].map(([currency, list]) => {
              const max = Math.max(...list.map((r) => Math.abs(r.groupTotalMinor)));
              return {
                currency,
                rows: list.map((r) => ({
                  ...r,
                  percent:
                    max === 0 ? 0 : Math.round((Math.abs(r.groupTotalMinor) / max) * 1e6) / 1e4,
                })),
              };
            }),
          );
          this.shownBy.set(by);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.error.set(translate('analytics.loadFailed'));
        },
      });
  }
}
