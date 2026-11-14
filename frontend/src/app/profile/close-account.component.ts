import { Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { translate } from '../core/i18n/i18n.service';

@Component({
  selector: 'app-close-account',
  imports: [TranslatePipe, ReactiveFormsModule, RouterLink],
  template: `
    <section class="dc-card" aria-labelledby="close-account-title">
      <h2 id="close-account-title">{{ 'closeAccount.closeAccount' | t }}</h2>
      <p>
        {{ 'closeAccount.closingIsPermanentYou' | t }}
      </p>
      <p>
        {{ 'closeAccount.downloadYourHistoryFirst' | t }}
        <a routerLink="/dashboard">{{ 'closeAccount.yourGroups' | t }}</a
        >.
      </p>
      @if (!opened()) {
        <button type="button" class="dc-btn-outline" (click)="opened.set(true)">
          {{ 'closeAccount.closeAccount2' | t }}
        </button>
      } @else {
        <form (submit)="$event.preventDefault(); close()">
          <label for="close-account-password">{{ 'common.currentPassword' | t }}</label>
          <input
            id="close-account-password"
            type="password"
            autocomplete="current-password"
            [formControl]="password"
          />
          <label>
            <input type="checkbox" [formControl]="confirmed" />
            {{ 'closeAccount.iUnderstandThatClosing' | t }}
          </label>
          <button type="submit" class="dc-btn-outline" [disabled]="busy()">
            {{ 'closeAccount.closeMyAccount' | t }}
          </button>
        </form>
      }
      @if (error()) {
        <p role="alert">{{ error() }}</p>
      }
    </section>
  `,
  styles: `
    :host {
      display: block;
    }
    section {
      padding: var(--sp-6);
    }
    h2 {
      margin-top: 0;
    }
    form {
      display: grid;
      gap: var(--sp-3);
      justify-items: start;
    }
  `,
})
export class CloseAccountComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly password = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required],
  });
  protected readonly confirmed = new FormControl(false, {
    nonNullable: true,
    validators: [Validators.requiredTrue],
  });
  protected readonly opened = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal('');

  close(): void {
    if (this.busy() || this.password.invalid || this.confirmed.invalid) {
      this.error.set(translate('closeAccount.enterYourCurrentPassword'));
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.auth.closeAccount(this.password.value).subscribe({
      next: () =>
        void this.router.navigate(['/login'], { queryParams: { reason: 'account-closed' } }),
      error: (err) => {
        this.busy.set(false);
        this.error.set(err?.error?.message ?? translate('closeAccount.yourAccountCouldNot'));
      },
    });
  }
}
