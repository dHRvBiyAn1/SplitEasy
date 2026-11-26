import { HttpClient } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { takeUntil } from 'rxjs';
import { API_BASE_URL } from '../core/api/api.service';
import { SessionService } from '../core/auth/session.service';
import { translate } from '../core/i18n/i18n.service';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { saveBlob } from '../shared/save-blob';

/** Profile section: stream my own data as a ZIP (profile, groups, ledgers, CSV per scope). */
@Component({
  selector: 'app-download-my-data',
  imports: [TranslatePipe],
  template: `
    <section class="dc-card" aria-labelledby="backup-heading">
      <h2 id="backup-heading">{{ 'backup.title' | t }}</h2>
      <p class="dc-caption">{{ 'backup.description' | t }}</p>
      <button type="button" class="dc-btn-outline" [disabled]="busy()" (click)="download()">
        {{ busy() ? ('backup.preparing' | t) : ('backup.download' | t) }}
      </button>
      @if (error()) {
        <p role="alert">{{ error() }}</p>
      }
    </section>
  `,
})
export class DownloadMyDataComponent {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);
  private readonly session = inject(SessionService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected download() {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    this.http
      .get(`${this.baseUrl}/me/export`, { responseType: 'blob' })
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (blob) => {
          this.busy.set(false);
          saveBlob(blob, `spliteasy-export-${new Date().toISOString().slice(0, 10)}.zip`);
        },
        error: () => {
          this.busy.set(false);
          this.error.set(translate('backup.failed'));
        },
      });
  }
}
