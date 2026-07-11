import { Injectable, inject, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { ApiService } from '../core/api/api.service';
import { DashboardResponse } from './dashboard.models';

/**
 * Owns the single `/api/dashboard` payload. Cached in a signal so both the app-shell
 * sidebar (group balances) and the dashboard page read one fetch. Call {@link refresh}
 * after any mutation (new expense / group / settlement) to re-pull.
 */
@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly api = inject(ApiService);

  private readonly _data = signal<DashboardResponse | null>(null);
  private readonly _loading = signal(false);
  readonly data = this._data.asReadonly();
  readonly loading = this._loading.asReadonly();

  /** Fetch only if we don't already have data and aren't mid-flight. */
  ensureLoaded(): void {
    if (this._data() === null && !this._loading()) {
      this.refresh();
    }
  }

  /** Force a re-fetch (after a mutation). */
  refresh(): void {
    this._loading.set(true);
    this.api
      .get<DashboardResponse>('/dashboard')
      .pipe(finalize(() => this._loading.set(false)))
      .subscribe({
        next: (res) => this._data.set(res),
        error: () => this._data.set(null),
      });
  }

  /** Clear cached data on logout so the next user doesn't see stale numbers. */
  clear(): void {
    this._data.set(null);
  }
}
