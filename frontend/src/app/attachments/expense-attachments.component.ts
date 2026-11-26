import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, effect, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subscription, takeUntil } from 'rxjs';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';
import { translate } from '../core/i18n/i18n.service';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { saveBlob } from '../shared/save-blob';
import { AttachmentService, ExpenseAttachment } from './attachment.service';

const MAX_BYTES = 5 * 1024 * 1024;
const ACCEPT = 'image/jpeg,image/png,image/webp,application/pdf';
const EXTENSIONS = /\.(jpe?g|png|webp|pdf)$/i;
const TYPES = new Set(ACCEPT.split(','));

/**
 * Receipts on one expense. Trust boundary: these are user uploads, so they are only ever offered as
 * downloads (blob + short-lived object URL) — never an `<img>`, `<iframe>` or `<object>`. The
 * client checks below are a convenience; the server's type/size checks are the authority.
 */
@Component({
  selector: 'app-expense-attachments',
  imports: [TranslatePipe],
  template: `
    <section class="notes" [attr.aria-label]="'attachments.title' | t">
      <h4>{{ 'attachments.title' | t }}</h4>
      @if (loading()) {
        <p role="status">{{ 'attachments.loading' | t }}</p>
      } @else if (loadError()) {
        <p role="alert">{{ loadError() }}</p>
        <button type="button" class="dc-btn-outline" (click)="load()">
          {{ 'common.tryAgain' | t }}
        </button>
      } @else {
        @if (!files().length) {
          <p class="dc-caption">{{ 'attachments.none' | t }}</p>
        }
        <ul>
          @for (a of files(); track a.id) {
            <li>
              <span class="name">{{ a.fileName }}</span>
              <small>
                {{ size(a.sizeBytes) }} ·
                {{ 'attachments.uploadedBy' | t: { name: a.uploadedBy.displayName } }}</small
              >
              <button
                type="button"
                class="dc-btn-outline"
                data-test="download-attachment"
                [disabled]="busy()"
                (click)="download(a)"
              >
                {{ 'attachments.download' | t }}
              </button>
              @if (a.uploadedBy.id === me()) {
                <button
                  type="button"
                  class="dc-btn-ghost"
                  data-test="delete-attachment"
                  [disabled]="readOnly() || busy()"
                  (click)="remove(a)"
                >
                  {{ 'attachments.delete' | t }}
                </button>
              }
            </li>
          }
        </ul>
      }
      @if (readOnly()) {
        <p class="dc-caption">{{ 'attachments.readOnly' | t }}</p>
      }
      <label>
        <span class="dc-field-label">{{ 'attachments.upload' | t }}</span>
        <input
          type="file"
          class="dc-field"
          [accept]="accept"
          [disabled]="readOnly() || busy()"
          (change)="pick($event)"
        />
      </label>
      <p class="dc-caption">{{ 'attachments.limits' | t }}</p>
      @if (error()) {
        <p role="alert">{{ error() }}</p>
      }
    </section>
  `,
  styles: `
    ul {
      list-style: none;
      padding: 0;
      display: grid;
      gap: var(--sp-3);
    }
    li {
      display: flex;
      flex-wrap: wrap;
      gap: var(--sp-2);
      align-items: center;
    }
    .name {
      overflow-wrap: anywhere;
    }
  `,
})
export class ExpenseAttachmentsComponent {
  readonly base = input.required<string>();
  readonly readOnly = input(false);
  private readonly service = inject(AttachmentService);
  private readonly session = inject(SessionService);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);
  private request?: Subscription;

  protected readonly accept = ACCEPT;
  protected readonly files = signal<ExpenseAttachment[]>([]);
  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly me = () => this.auth.user()?.id;

  constructor() {
    effect(() => {
      this.base();
      this.files.set([]);
      this.error.set(null);
      this.load();
    });
    this.session.changes.pipe(takeUntilDestroyed()).subscribe(() => {
      this.request?.unsubscribe();
      this.files.set([]);
    });
  }

  private scoped<T>(source: Observable<T>) {
    return source.pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef));
  }

  protected size(bytes: number): string {
    return bytes < 1024 * 1024
      ? translate('attachments.sizeKb', { n: Math.max(1, Math.round(bytes / 1024)) })
      : translate('attachments.sizeMb', { n: (bytes / (1024 * 1024)).toFixed(1) });
  }

  protected load() {
    this.request?.unsubscribe();
    this.loading.set(true);
    this.loadError.set(null);
    this.request = this.scoped(this.service.list(this.base())).subscribe({
      next: (rows) => {
        this.files.set(rows);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.loadError.set(translate('attachments.loadFailed'));
      },
    });
  }

  protected pick(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || this.busy() || this.readOnly()) return;
    if (!TYPES.has(file.type) || !EXTENSIONS.test(file.name)) {
      this.error.set(translate('attachments.badType'));
      return;
    }
    if (file.size > MAX_BYTES) {
      this.error.set(translate('attachments.tooBig'));
      return;
    }
    this.run(this.service.upload(this.base(), file), (created) =>
      this.files.update((rows) => [...rows, created]),
    );
  }

  protected download(a: ExpenseAttachment) {
    this.run(this.service.download(this.base(), a.id), (blob) => saveBlob(blob, a.fileName));
  }

  protected remove(a: ExpenseAttachment) {
    this.run(this.service.remove(this.base(), a.id), () =>
      this.files.update((rows) => rows.filter((r) => r.id !== a.id)),
    );
  }

  private run<T>(call: Observable<T>, done: (value: T) => void) {
    this.busy.set(true);
    this.error.set(null);
    this.scoped(call).subscribe({
      next: (value) => {
        this.busy.set(false);
        done(value);
      },
      error: (e: HttpErrorResponse) => {
        this.busy.set(false);
        this.error.set(this.message(e));
      },
    });
  }

  private message(e: HttpErrorResponse): string {
    if (e.status === 413) return translate('attachments.tooBig');
    if (e.status === 400) return translate('attachments.rejected');
    if (e.status === 409) return translate('attachments.conflict');
    return translate('attachments.failed');
  }
}
