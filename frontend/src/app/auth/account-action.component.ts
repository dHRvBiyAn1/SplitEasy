import { takeUntil } from 'rxjs';
import { Component, DestroyRef, ElementRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { newPasswordValidators } from './password-validation';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';
import { ProfileService } from '../core/profile.service';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { translate } from '../core/i18n/i18n.service';

@Component({
  selector: 'app-account-action',
  imports: [TranslatePipe, ReactiveFormsModule, RouterLink],
  styleUrl: './auth.scss',
  template: `<div class="auth__panel">
    <main class="auth__form-wrap">
      <h1 class="dc-display auth__title">
        {{
          action() === 'password-reset'
            ? ('forgotPassword.resetYourPassword' | t)
            : action() === 'email-change'
              ? ('accountAction.confirmEmailChange' | t)
              : ('accountAction.verifyEmail' | t)
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
              ><span class="dc-field-label">{{ 'common.newPassword' | t }}</span
              ><input
                class="dc-field"
                type="password"
                autocomplete="new-password"
                [formControl]="password"
                [attr.aria-invalid]="password.touched && password.invalid"
                aria-describedby="reset-password-hint"
            /></label>
            <p id="reset-password-hint">{{ 'common.useAtLeast8' | t }}</p>
          }
          <button type="submit" class="dc-btn-accent" [disabled]="pending()">
            {{
              pending()
                ? ('profile.saving' | t)
                : action() === 'password-reset'
                  ? ('accountAction.resetPassword' | t)
                  : action() === 'email-change'
                    ? ('accountAction.confirmEmailChange' | t)
                    : ('accountAction.verifyEmail' | t)
            }}
          </button>
        </form>
      }
      <p class="auth__switch">
        <a routerLink="/login">{{ 'common.logIn' | t }}</a> ·
        <a routerLink="/forgot-password">{{ 'accountAction.requestANewReset' | t }}</a> ·
        <a routerLink="/profile">{{ 'common.accountSecurityAndVerification' | t }}</a>
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
      if (!this.message()) this.error.set(translate('accountAction.yourSessionChangedReopen'));
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
      this.error.set(translate('accountAction.anAccountActionIs'));
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
    } else this.error.set(translate('accountAction.reopenTheLinkFrom'));
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
            ? translate('accountAction.passwordResetLogIn')
            : this.action() === 'email-change'
              ? translate('accountAction.emailChangedLogIn')
              : translate('accountAction.emailVerified'),
        );
      },
      error: (err) => {
        this.pending.set(false);
        if (this.action() === 'email-change' && err.status === 409) {
          this.token = '';
          this.valid.set(false);
          this.error.set(translate('accountAction.thisEmailAddressIs'));
          return;
        }
        this.error.set(
          err.status === 429
            ? translate('accountAction.tooManyAttemptsPlease')
            : err.status === 400
              ? translate('accountAction.thisLinkIsInvalid')
              : translate('accountAction.temporarilyUnavailablePleaseTry'),
        );
      },
    });
  }
}
