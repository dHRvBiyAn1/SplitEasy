import { Component, DestroyRef, ElementRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { AuthHeroComponent } from './auth-hero.component';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { translate } from '../core/i18n/i18n.service';

@Component({
  selector: 'app-forgot-password',
  imports: [TranslatePipe, ReactiveFormsModule, RouterLink, AuthHeroComponent],
  styleUrl: './auth.scss',
  template: `<div class="auth">
    <app-auth-hero class="auth__hero" />
    <div class="auth__panel">
      <div class="auth__form-wrap">
        <h1 class="dc-display auth__title">{{ 'forgotPassword.resetYourPassword' | t }}</h1>
        <form class="auth__form" (submit)="$event.preventDefault(); submit()" novalidate>
          <label class="auth__field"
            ><span class="dc-field-label">{{ 'common.email' | t }}</span
            ><input
              class="dc-field"
              type="email"
              autocomplete="email"
              [formControl]="email"
              [attr.aria-invalid]="email.touched && email.invalid"
              aria-describedby="recovery-email-hint"
          /></label>
          @if (email.touched && email.invalid) {
            <p class="auth__field-error" id="recovery-email-hint">
              {{ 'common.enterAValidEmail' | t }}
            </p>
          }
          @if (error()) {
            <p class="auth__error" role="alert">{{ error() }}</p>
          }
          @if (message()) {
            <p role="status">{{ message() }}</p>
          }
          <button class="dc-btn-accent auth__submit" type="submit" [disabled]="pending()">
            {{
              pending()
                ? ('forgotPassword.requesting' | t)
                : ('forgotPassword.requestResetLink' | t)
            }}
          </button>
        </form>
        <p class="auth__switch">
          <a routerLink="/login">{{ 'forgotPassword.backToLogIn' | t }}</a>
        </p>
      </div>
    </div>
  </div>`,
})
export class ForgotPasswordComponent {
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly auth = inject(AuthService);
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
      this.element.nativeElement.querySelector<HTMLInputElement>('input')?.focus();
      return;
    }
    if (this.pending()) return;
    this.pending.set(true);
    this.error.set('');
    this.message.set('');
    this.auth
      .requestPasswordReset(this.email.value.trim())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.pending.set(false);
          this.message.set(translate('forgotPassword.ifAnAccountExists'));
        },
        error: (err) => {
          this.pending.set(false);
          this.error.set(
            err.status === 429
              ? translate('accountAction.tooManyAttemptsPlease')
              : err.status === 503
                ? translate('forgotPassword.emailIsTemporarilyUnavailable')
                : translate('forgotPassword.unableToRequestA'),
          );
        },
      });
  }
}
