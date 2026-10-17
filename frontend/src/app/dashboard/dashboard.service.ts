import { Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { ApiService } from '../core/api/api.service';
import { SessionService } from '../core/auth/session.service';
import { DashboardResponse } from './dashboard.models';

/**
 * Owns the single `/api/dashboard` payload. Cached in a signal so both the app-shell
 * group tray and the dashboard page read one fetch. Call {@link refresh}
 * after any mutation (new expense / group / settlement) to re-pull.
 */
@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly api = inject(ApiService);
  private cacheVersion = 0;

  private readonly _data = signal<DashboardResponse | null>(null);
  private readonly _loading = signal(false);
  private readonly _error = signal(false);
  readonly data = this._data.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  constructor() {
    inject(SessionService)
      .changes.pipe(takeUntilDestroyed())
      .subscribe(() => this.clear());
  }

  /** Fetch only if we don't already have data and aren't mid-flight. */
  ensureLoaded(): void {
    if (this._data() === null && !this._loading()) {
      this.refresh();
    }
  }

  /** Force a re-fetch (after a mutation). */
  refresh(): void {
    const version = this.cacheVersion;
    this._loading.set(true);
    this._error.set(false);
    this.api
      .get<DashboardResponse>('/dashboard')
      .pipe(
        finalize(() => {
          if (version === this.cacheVersion) this._loading.set(false);
        }),
      )
      .subscribe({
        next: (res) => {
          if (version === this.cacheVersion) this._data.set(res);
        },
        error: () => {
          if (version === this.cacheVersion) this._error.set(true);
        },
      });
  }

  /** Invalidate outstanding responses as well as cached data at a session boundary. */
  clear(): void {
    this.cacheVersion++;
    this._data.set(null);
    this._loading.set(false);
    this._error.set(false);
  }
}
