import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api/api.service';
import { AuthHeroComponent } from './auth-hero.component';

@Component({
  selector: 'app-forgot-password',
  imports: [ReactiveFormsModule, RouterLink, AuthHeroComponent],
  styleUrl: './auth.scss',
  template: `<div class="auth">
    <app-auth-hero class="auth__hero" />
    <div class="auth__panel">
      <div class="auth__form-wrap">
        <h1 class="dc-display auth__title">Reset your password</h1>
        <form class="auth__form" (submit)="$event.preventDefault(); submit()" novalidate>
          <label class="auth__field"
            ><span class="dc-field-label">Email</span
            ><input
              class="dc-field"
              type="email"
              autocomplete="email"
              [formControl]="email"
              [attr.aria-invalid]="email.touched && email.invalid"
              aria-describedby="recovery-email-hint"
          /></label>
          @if (email.touched && email.invalid) {
            <p class="auth__field-error" id="recovery-email-hint">Enter a valid email address.</p>
          }
          @if (error()) {
            <p class="auth__error" role="alert">{{ error() }}</p>
          }
          @if (message()) {
            <p role="status">{{ message() }}</p>
          }
          <button class="dc-btn-accent auth__submit" type="submit" [disabled]="pending()">
            {{ pending() ? 'Requesting…' : 'Request reset link' }}
          </button>
        </form>
        <p class="auth__switch"><a routerLink="/login">Back to log in</a></p>
      </div>
    </div>
  </div>`,
})
export class ForgotPasswordComponent {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly email = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.email],
  });
  protected readonly pending = signal(false);
  protected readonly error = signal('');
  protected readonly message = signal('');
  submit(): void {
    if (this.email.invalid) {
      this.email.markAsTouched();
      return;
    }
    if (this.pending()) return;
    this.pending.set(true);
    this.error.set('');
    this.message.set('');
    this.api
      .post<void>('/auth/password-reset-requests', { email: this.email.value.trim() })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.pending.set(false);
          this.message.set(
            'If an account exists, a link may arrive; retry or contact support if it does not.',
          );
        },
        error: (err) => {
          this.pending.set(false);
          this.error.set(
            err.status === 429
              ? 'Too many attempts. Please wait before trying again.'
              : err.status === 503
                ? 'Email is temporarily unavailable. Please try again later.'
                : 'Unable to request a link. Please try again.',
          );
        },
      });
  }
}
