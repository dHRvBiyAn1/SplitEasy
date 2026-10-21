import { takeUntil } from 'rxjs';
import { Component, DestroyRef, ElementRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { newPasswordValidators } from './password-validation';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';
import { ProfileService } from '../core/profile.service';

@Component({
  selector: 'app-account-action',
  imports: [ReactiveFormsModule, RouterLink],
  styleUrl: './auth.scss',
  template: `<div class="auth__panel">
    <main class="auth__form-wrap">
      <h1 class="dc-display auth__title">
        {{
          action() === 'password-reset'
            ? 'Reset your password'
            : action() === 'email-change'
              ? 'Confirm email change'
              : 'Verify email'
        }}
      </h1>
      @if (message()) {
        <p role="status">{{ message() }}</p>
      }
      @if (error()) {
        <p class="auth__error" role="alert">{{ error() }}</p>
      }
      @if (valid() && !message()) {
        <form class="auth__form" (submit)="$event.preventDefault(); submit()" novalidate>
          @if (action() === 'password-reset') {
            <label class="auth__field"
              ><span class="dc-field-label">New password</span
              ><input
                class="dc-field"
                type="password"
                autocomplete="new-password"
                [formControl]="password"
                [attr.aria-invalid]="password.touched && password.invalid"
                aria-describedby="reset-password-hint"
            /></label>
            <p id="reset-password-hint">Use at least 8 characters and at most 72 UTF-8 bytes.</p>
          }
          <button type="submit" class="dc-btn-accent" [disabled]="pending()">
            {{
              pending()
                ? 'Saving…'
                : action() === 'password-reset'
                  ? 'Reset password'
                  : action() === 'email-change'
                    ? 'Confirm email change'
                    : 'Verify email'
            }}
          </button>
        </form>
      }
      <p class="auth__switch">
        <a routerLink="/login">Log in</a> ·
        <a routerLink="/forgot-password">Request a new reset link</a> ·
        <a routerLink="/profile">Account security and verification</a>
      </p>
    </main>
  </div>`,
})
export class AccountActionComponent {
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly auth = inject(AuthService);
  private readonly session = inject(SessionService);
  private readonly profile = inject(ProfileService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private token = '';
  protected readonly action = signal('');
  protected readonly valid = signal(false);
  protected readonly password = new FormControl('', {
    nonNullable: true,
    validators: newPasswordValidators,
  });
  protected readonly pending = signal(false);
  protected readonly error = signal('');
  protected readonly message = signal('');
  constructor() {
    this.consumeFragment();
    const reopen = () => this.consumeFragment();
    window.addEventListener('hashchange', reopen);
    this.router.events.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((event) => {
      // Router completion can restore a fragment already consumed by the native hash handler.
      if (event instanceof NavigationEnd && location.hash)
        history.replaceState(history.state, '', location.pathname + location.search);
    });
    this.session.changes.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.token = '';
      this.password.reset();
      this.valid.set(false);
      this.pending.set(false);
      if (!this.message())
        this.error.set('Your session changed. Reopen the link from your email to continue.');
    });
    this.destroyRef.onDestroy(() => {
      window.removeEventListener('hashchange', reopen);
      this.token = '';
      this.password.reset();
    });
  }
  private consumeFragment(): void {
    const fragment = new URLSearchParams(location.hash.slice(1));
    history.replaceState(history.state, '', location.pathname + location.search);
    if (this.pending()) {
      this.error.set(
        'An account action is already pending. Wait for it to finish, then reopen your email link.',
      );
      return;
    }
    this.token = '';
    this.password.reset();
    this.action.set('');
    this.valid.set(false);
    this.message.set('');
    this.error.set('');
    const token = fragment.get('token') ?? '';
    if (
      fragment.getAll('action').length === 1 &&
      fragment.getAll('token').length === 1 &&
      ['password-reset', 'email-verification', 'email-change'].includes(
        fragment.get('action') ?? '',
      ) &&
      /^[A-Za-z0-9_-]{43}$/.test(token)
    ) {
      this.token = token;
      this.action.set(fragment.get('action')!);
      this.valid.set(true);
    } else this.error.set('Reopen the link from your email, or request a new link.');
  }
  submit(): void {
    if (!this.valid() || this.pending()) return;
    if (this.action() === 'password-reset' && this.password.invalid) {
      this.password.markAsTouched();
      this.element.nativeElement.querySelector<HTMLInputElement>('input[type=password]')?.focus();
      return;
    }
    this.error.set('');
    this.pending.set(true);
    const id = this.auth.user()?.id;
    const version = this.session.version;
    const reset = this.action() === 'password-reset';
    const request = reset
      ? this.auth.resetPassword(this.token, this.password.value)
      : this.action() === 'email-change'
        ? this.auth.confirmEmailChange(this.token)
        : this.auth.verifyEmail(this.token);
    request.pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.token = '';
        this.password.reset();
        this.pending.set(false);
        this.error.set('');
        if (id && this.auth.user()?.id === id && this.session.version === version)
          this.profile.refresh();
        this.message.set(
          reset
            ? 'Password reset. Log in with your new password.'
            : this.action() === 'email-change'
              ? 'Email changed. Log in with your new email.'
              : 'Email verified.',
        );
      },
      error: (err) => {
        this.pending.set(false);
        this.error.set(
          err.status === 429
            ? 'Too many attempts. Please wait before trying again.'
            : err.status === 400
              ? 'This link is invalid or expired. Request a new link.'
              : 'Temporarily unavailable. Please try again later.',
        );
      },
    });
  }
}
