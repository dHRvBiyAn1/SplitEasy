import { Component, DestroyRef, effect, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, takeUntil } from 'rxjs';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';
import { translate } from '../core/i18n/i18n.service';
import { LocaleDatePipe } from '../core/i18n/locale-date.pipe';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { CommentService, ExpenseComment } from './comment.service';

const MAX_LENGTH = 2000;

/** Comments on one expense; the same panel serves group receipts and personal expenses. */
@Component({
  selector: 'app-expense-comments',
  imports: [TranslatePipe, LocaleDatePipe],
  template: `
    <section class="notes" [attr.aria-label]="'comments.title' | t">
      <h4>{{ 'comments.title' | t }}</h4>
      @if (loading()) {
        <p role="status">{{ 'comments.loading' | t }}</p>
      } @else if (loadError()) {
        <p role="alert">{{ loadError() }}</p>
        <button type="button" class="dc-btn-outline" (click)="load()">
          {{ 'common.tryAgain' | t }}
        </button>
      } @else {
        @if (!comments().length) {
          <p class="dc-caption">{{ 'comments.none' | t }}</p>
        }
        <ul>
          @for (c of comments(); track c.id) {
            <li>
              <p>
                <strong>{{ c.author.displayName }}</strong>
                <small>
                  · <time [attr.datetime]="c.createdAt">{{ c.createdAt | ldate }}</time></small
                >
              </p>
              <p class="body">{{ c.body }}</p>
              @if (c.author.id === me()) {
                <button
                  type="button"
                  class="dc-btn-ghost"
                  data-test="delete-comment"
                  [disabled]="readOnly() || busy()"
                  (click)="remove(c)"
                >
                  {{ 'comments.delete' | t }}
                </button>
              }
            </li>
          }
        </ul>
      }
      @if (readOnly()) {
        <p class="dc-caption">{{ 'comments.readOnly' | t }}</p>
      }
      <form (submit)="$event.preventDefault(); add()">
        <label>
          <span class="dc-field-label">{{ 'comments.add' | t }}</span>
          <textarea
            class="dc-field"
            name="comment"
            rows="2"
            [maxLength]="max"
            [value]="draft()"
            [disabled]="readOnly() || busy()"
            (input)="draft.set($any($event.target).value)"
          ></textarea>
        </label>
        @if (error()) {
          <p role="alert">{{ error() }}</p>
        }
        <button
          type="submit"
          class="dc-btn"
          [disabled]="readOnly() || busy() || !draft().trim() || loading()"
        >
          {{ 'comments.post' | t }}
        </button>
      </form>
    </section>
  `,
  styles: `
    ul {
      list-style: none;
      padding: 0;
      display: grid;
      gap: var(--sp-3);
    }
    .body {
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }
    form {
      display: grid;
      gap: var(--sp-2);
    }
  `,
})
export class ExpenseCommentsComponent {
  readonly base = input.required<string>();
  readonly readOnly = input(false);
  private readonly service = inject(CommentService);
  private readonly session = inject(SessionService);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);
  private request?: Subscription;

  protected readonly max = MAX_LENGTH;
  protected readonly comments = signal<ExpenseComment[]>([]);
  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly draft = signal('');
  protected readonly me = () => this.auth.user()?.id;

  constructor() {
    effect(() => {
      this.base();
      this.comments.set([]);
      this.draft.set('');
      this.load();
    });
    this.session.changes.pipe(takeUntilDestroyed()).subscribe(() => {
      this.request?.unsubscribe();
      this.comments.set([]);
    });
  }

  /** Session or destroy ends every request; a base change replaces the previous one. */
  private scoped<T>(source: import('rxjs').Observable<T>) {
    return source.pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef));
  }

  protected load() {
    this.request?.unsubscribe();
    this.loading.set(true);
    this.loadError.set(null);
    this.request = this.scoped(this.service.list(this.base())).subscribe({
      next: (rows) => {
        this.comments.set(rows);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.loadError.set(translate('comments.loadFailed'));
      },
    });
  }

  protected add() {
    const body = this.draft().trim();
    if (!body || this.busy() || this.readOnly()) return;
    this.busy.set(true);
    this.error.set(null);
    this.scoped(this.service.add(this.base(), body)).subscribe({
      next: (created) => {
        this.comments.update((rows) => [...rows, created]);
        this.draft.set('');
        this.busy.set(false);
      },
      // The draft stays so a failed post is never lost.
      error: () => {
        this.busy.set(false);
        this.error.set(translate('comments.addFailed'));
      },
    });
  }

  protected remove(comment: ExpenseComment) {
    if (this.busy() || this.readOnly()) return;
    this.busy.set(true);
    this.error.set(null);
    this.scoped(this.service.remove(this.base(), comment.id)).subscribe({
      next: () => {
        this.comments.update((rows) => rows.filter((r) => r.id !== comment.id));
        this.busy.set(false);
      },
      error: () => {
        this.busy.set(false);
        this.error.set(translate('comments.deleteFailed'));
      },
    });
  }
}
