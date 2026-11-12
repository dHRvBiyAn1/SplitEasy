import { Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';

@Component({
  selector: 'app-close-account',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <section class="dc-card" aria-labelledby="close-account-title">
      <h2 id="close-account-title">Close account</h2>
      <p>
        Closing is permanent. You can only close once every balance is settled. Other members keep
        the shared history, which shows you as “Closed account”; your email is released and your
        sign-ins end on every device.
      </p>
      <p>
        Download your history first from each group's History page, starting in
        <a routerLink="/dashboard">your groups</a>.
      </p>
      @if (!opened()) {
        <button type="button" class="dc-btn-outline" (click)="opened.set(true)">
          Close account…
        </button>
      } @else {
        <form (submit)="$event.preventDefault(); close()">
          <label for="close-account-password">Current password</label>
          <input
            id="close-account-password"
            type="password"
            autocomplete="current-password"
            [formControl]="password"
          />
          <label>
            <input type="checkbox" [formControl]="confirmed" />
            I understand that closing my account cannot be undone.
          </label>
          <button type="submit" class="dc-btn-outline" [disabled]="busy()">Close my account</button>
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
      this.error.set('Enter your current password and confirm to close your account.');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.auth.closeAccount(this.password.value).subscribe({
      next: () =>
        void this.router.navigate(['/login'], { queryParams: { reason: 'account-closed' } }),
      error: (err) => {
        this.busy.set(false);
        this.error.set(err?.error?.message ?? 'Your account could not be closed. Try again.');
      },
    });
  }
}
