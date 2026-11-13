import { Component, DestroyRef, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subject, Subscription, takeUntil } from 'rxjs';
import { MoneyComponent } from '../shared/money.component';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';
import { HistoryFilter, HistoryItem, HistoryType } from './history.models';
import { HistoryService } from './history.service';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { translate } from '../core/i18n/i18n.service';

const TYPE_LABEL: Record<HistoryType, string> = {
  EXPENSE: translate('shell.expense'),
  PAYMENT: translate('history.settlement'),
  REFUND: translate('history.refund'),
};

/** A group's financial history, newest first, a page at a time, with date filters and CSV export. */
@Component({
  selector: 'app-history',
  imports: [TranslatePipe, RouterLink, MoneyComponent],
  templateUrl: './history.component.html',
  styleUrl: './history.component.scss',
})
export class HistoryComponent {
  private readonly service = inject(HistoryService);
  private readonly session = inject(SessionService);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly scopeChanges = new Subject<void>();
  private request?: Subscription;
  private exporting?: Subscription;

  protected groupId = '';
  protected readonly items = signal<HistoryItem[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly exportBusy = signal(false);
  protected readonly exportError = signal<string | null>(null);
  protected readonly typeLabel = TYPE_LABEL;
  protected from = '';
  protected to = '';
  /** The filter the visible rows were loaded with, so "load more" and export match them. */
  private applied: HistoryFilter = {};

  constructor() {
    this.session.changes.pipe(takeUntilDestroyed()).subscribe(() => {
      this.reset();
      this.error.set(translate('history.sessionChangedTryAgain'));
    });
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.scopeChanges.next();
      this.reset();
      this.groupId = params.get('id') ?? '';
      this.from = '';
      this.to = '';
      this.applied = {};
      this.load();
    });
  }

  private reset() {
    this.request?.unsubscribe();
    this.exporting?.unsubscribe();
    this.items.set([]);
    this.nextCursor.set(null);
    this.loading.set(false);
    this.loadingMore.set(false);
    this.error.set(null);
    this.exportBusy.set(false);
    this.exportError.set(null);
  }

  protected applyFilter() {
    if (this.from && this.to && this.from > this.to) {
      this.error.set(translate('history.theStartDateMust'));
      return;
    }
    this.applied = { from: this.from || undefined, to: this.to || undefined };
    this.load();
  }

  protected clearFilter() {
    this.from = '';
    this.to = '';
    this.applied = {};
    this.load();
  }

  /** First page, replacing whatever is shown; a failure leaves a retry, never a half list. */
  protected load() {
    if (!this.groupId || !this.auth.isAuthenticated()) return;
    this.request?.unsubscribe();
    this.items.set([]);
    this.nextCursor.set(null);
    this.loadingMore.set(false);
    this.loading.set(true);
    this.error.set(null);
    this.request = this.service
      .page(this.groupId, this.applied)
      .pipe(this.cancel)
      .subscribe({
        next: (page) => {
          this.items.set(page.items);
          this.nextCursor.set(page.nextCursor);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.error.set(translate('history.couldNotLoadHistory'));
        },
      });
  }

  protected loadMore() {
    const cursor = this.nextCursor();
    if (!cursor || this.loadingMore() || this.loading()) return;
    this.loadingMore.set(true);
    this.error.set(null);
    this.request = this.service
      .page(this.groupId, this.applied, cursor)
      .pipe(this.cancel)
      .subscribe({
        next: (page) => {
          this.items.update((rows) => [...rows, ...page.items]);
          this.nextCursor.set(page.nextCursor);
          this.loadingMore.set(false);
        },
        error: () => {
          // Rows already loaded stay; the cursor is kept so the same page can be retried.
          this.loadingMore.set(false);
          this.error.set(translate('history.couldNotLoadMore'));
        },
      });
  }

  protected download() {
    if (this.exportBusy()) return;
    this.exportBusy.set(true);
    this.exportError.set(null);
    this.exporting = this.service
      .exportCsv(this.groupId, this.applied)
      .pipe(this.cancel)
      .subscribe({
        next: (blob) => {
          this.exportBusy.set(false);
          const url = URL.createObjectURL(blob);
          const link = document.createElement('a');
          link.href = url;
          link.download = 'history.csv';
          link.click();
          URL.revokeObjectURL(url);
        },
        error: () => {
          this.exportBusy.set(false);
          this.exportError.set(translate('history.couldNotExportHistory'));
        },
      });
  }

  /** Route, session and destroy cancellation shared by every read in this screen. */
  private readonly cancel = <T>(source: Observable<T>) =>
    source.pipe(
      takeUntil(this.scopeChanges),
      takeUntil(this.session.changes),
      takeUntilDestroyed(this.destroyRef),
    );
}
