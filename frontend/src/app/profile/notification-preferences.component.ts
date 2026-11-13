import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { takeUntil } from 'rxjs';
import { SessionService } from '../core/auth/session.service';
import { NotificationPreferences, NotificationService } from '../notification/notification.service';
import { TranslatePipe } from '../core/i18n/translate.pipe';

const SWITCHES: { key: keyof NotificationPreferences; label: string }[] = [
  { key: 'expenses', label: 'Expenses added, edited, deleted or restored' },
  { key: 'payments', label: 'Settlements and refunds' },
  { key: 'groupChanges', label: 'Group changes and member updates' },
];

/** Server-owned in-app notification switches; email and push are not offered yet. */
@Component({
  selector: 'app-notification-preferences',
  imports: [TranslatePipe],
  template: `
    <details class="pcard" (toggle)="open($any($event.target).open)">
      <summary>{{ 'notificationPreferences.notificationSettings' | t }}</summary>
      @if (loadError()) {
        <p role="alert">{{ loadError() }}</p>
        <button class="dc-btn-outline" type="button" (click)="load()">
          {{ 'common.tryAgain' | t }}
        </button>
      } @else if (prefs(); as p) {
        @for (s of switches; track s.key) {
          <label class="switch">
            <input
              type="checkbox"
              [checked]="p[s.key]"
              [disabled]="saving()"
              (change)="toggle(s.key, $any($event.target))"
            />
            {{ s.label }}
          </label>
        }
        @if (saveError()) {
          <p role="alert">{{ saveError() }}</p>
        }
      } @else {
        <p role="status">{{ 'notificationPreferences.loadingNotificationSettings' | t }}</p>
      }
    </details>
  `,
  styles: `
    .switch {
      display: flex;
      gap: var(--sp-3);
      align-items: center;
      padding: var(--sp-2) 0;
    }
  `,
})
export class NotificationPreferencesComponent {
  private readonly service = inject(NotificationService);
  private readonly session = inject(SessionService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly switches = SWITCHES;
  protected readonly prefs = signal<NotificationPreferences | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saveError = signal<string | null>(null);
  protected readonly saving = signal(false);

  constructor() {
    this.session.changes.pipe(takeUntilDestroyed()).subscribe(() => {
      this.prefs.set(null);
      this.requested = false;
      this.saving.set(false);
    });
  }

  /** Loaded on first expansion, so the profile page makes no extra request on open. */
  protected open(isOpen: boolean) {
    if (isOpen && !this.prefs() && !this.requested) this.load();
  }

  private requested = false;

  protected load() {
    this.requested = true;
    this.loadError.set(null);
    this.service
      .preferences()
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (p) => this.prefs.set(p),
        error: () => {
          this.requested = false;
          this.loadError.set('Could not load notification settings.');
        },
      });
  }

  protected toggle(key: keyof NotificationPreferences, input: HTMLInputElement) {
    const value = input.checked;
    const current = this.prefs();
    if (!current) return;
    this.saving.set(true);
    this.saveError.set(null);
    this.service
      .savePreferences({ ...current, [key]: value })
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (p) => {
          this.prefs.set(p);
          this.saving.set(false);
        },
        error: () => {
          input.checked = !value; // the server kept the old value
          this.saving.set(false);
          this.saveError.set('Could not save that setting. It was not changed.');
        },
      });
  }
}
