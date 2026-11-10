import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { newPasswordValidators, passwordBytes } from '../auth/password-validation';
import {
  ChangeDetectorRef,
  Component,
  DestroyRef,
  ElementRef,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { takeUntil } from 'rxjs';
import { ProfileService } from '../core/profile.service';
import { SessionService } from '../core/auth/session.service';

@Component({
  selector: 'app-account-security',
  imports: [ReactiveFormsModule],
  template: `<section class="dc-card security" aria-labelledby="account-security-title">
    <h2 id="account-security-title">Account security</h2>
    <p>
      <span class="security__badge">{{
        profile.data()?.emailVerified ? 'Email verified' : 'Email not verified'
      }}</span>
    </p>
    @if (!profile.data()?.emailVerified) {
      <button
        class="dc-btn-outline"
        type="button"
        [disabled]="pending()"
        (click)="requestVerification()"
      >
        Send verification link
      </button>
    }
    @if (mode() === '') {
      <button
        class="dc-btn-outline"
        type="button"
        [disabled]="pending()"
        (click)="mode.set('password')"
      >
        Change password</button
      ><button
        class="dc-btn-outline"
        type="button"
        [disabled]="pending()"
        (click)="mode.set('email')"
      >
        Change email
      </button>
    }
    @if (mode() === 'password') {
      <form
        id="password-change-form"
        [formGroup]="passwordForm"
        (ngSubmit)="changePassword()"
        novalidate
      >
        @if (
          passwordForm.controls.currentPassword.touched &&
          passwordForm.controls.currentPassword.invalid &&
          passwordForm.controls.password.touched &&
          passwordForm.controls.password.invalid
        ) {
          <div
            id="password-errors"
            tabindex="-1"
            role="alert"
            aria-labelledby="password-errors-title"
          >
            <p id="password-errors-title">Check these password fields:</p>
            <ul>
              <li>
                <a
                  href="#security-current-password"
                  (click)="$event.preventDefault(); focusTarget('security-current-password')"
                  >Enter your current password (at most 72 UTF-8 bytes).</a
                >
              </li>
              <li>
                <a
                  href="#security-new-password"
                  (click)="$event.preventDefault(); focusTarget('security-new-password')"
                  >Use a new password of at least 8 characters and at most 72 UTF-8 bytes.</a
                >
              </li>
            </ul>
          </div>
        }
        <label
          ><span class="dc-field-label">Current password</span
          ><input
            id="security-current-password"
            class="dc-field"
            type="password"
            formControlName="currentPassword"
            autocomplete="current-password"
            [readOnly]="pending()"
            [attr.aria-invalid]="
              passwordForm.controls.currentPassword.touched &&
              passwordForm.controls.currentPassword.invalid
            "
            aria-describedby="security-password-hint"
        /></label>
        <label
          ><span class="dc-field-label">New password</span
          ><input
            id="security-new-password"
            class="dc-field"
            type="password"
            formControlName="password"
            autocomplete="new-password"
            [readOnly]="pending()"
            [attr.aria-invalid]="
              passwordForm.controls.password.touched && passwordForm.controls.password.invalid
            "
            aria-describedby="security-password-hint"
        /></label>
        <p id="security-password-hint">
          Enter your current password. New passwords need at least 8 characters and at most 72 UTF-8
          bytes.
        </p>
        <button class="dc-btn-accent" type="submit" [disabled]="pending()">
          {{ pending() ? 'Saving…' : 'Save password' }}
        </button>
        <button class="dc-btn-outline" type="button" [disabled]="pending()" (click)="cancel()">
          Cancel
        </button>
      </form>
    }
    @if (mode() === 'email') {
      <form id="email-change-form" [formGroup]="emailForm" (ngSubmit)="changeEmail()" novalidate>
        @if (
          emailForm.controls.currentPassword.touched &&
          emailForm.controls.currentPassword.invalid &&
          emailForm.controls.email.touched &&
          emailForm.controls.email.invalid
        ) {
          <div id="email-errors" tabindex="-1" role="alert" aria-labelledby="email-errors-title">
            <p id="email-errors-title">Check these email-change fields:</p>
            <ul>
              <li>
                <a
                  href="#email-current-password"
                  (click)="$event.preventDefault(); focusTarget('email-current-password')"
                  >Enter your current password (at most 72 UTF-8 bytes).</a
                >
              </li>
              <li>
                <a
                  href="#security-new-email"
                  (click)="$event.preventDefault(); focusTarget('security-new-email')"
                  >Enter a valid new email address of at most 254 characters.</a
                >
              </li>
            </ul>
          </div>
        }
        <label
          ><span class="dc-field-label">Current password</span
          ><input
            id="email-current-password"
            class="dc-field"
            type="password"
            formControlName="currentPassword"
            autocomplete="current-password"
            [readOnly]="pending()"
            [attr.aria-invalid]="
              emailForm.controls.currentPassword.touched &&
              emailForm.controls.currentPassword.invalid
            "
            aria-describedby="email-change-hint"
        /></label>
        <label
          ><span class="dc-field-label">New email</span
          ><input
            id="security-new-email"
            class="dc-field"
            type="email"
            formControlName="email"
            autocomplete="email"
            [readOnly]="pending()"
            [attr.aria-invalid]="
              emailForm.controls.email.touched && emailForm.controls.email.invalid
            "
            aria-describedby="email-change-hint"
        /></label>
        <p id="email-change-hint">
          Enter your current password (at most 72 UTF-8 bytes) and a valid new email. Your current
          email remains your login until confirmation in the new mailbox.
        </p>
        <button class="dc-btn-accent" type="submit" [disabled]="pending()">
          {{ pending() ? 'Requesting…' : 'Request email change' }}
        </button>
        <button class="dc-btn-outline" type="button" [disabled]="pending()" (click)="cancel()">
          Cancel
        </button>
      </form>
    }
    @if (message()) {
      <p role="status">{{ message() }}</p>
    }
    @if (error()) {
      <p role="alert">{{ error() }}</p>
    }
  </section>`,
  styles: `
    .security {
      padding: var(--sp-6);
      display: grid;
      gap: var(--sp-4);
      min-width: 0;
      overflow-wrap: anywhere;
    }
    .security form,
    .security label {
      display: grid;
      gap: var(--sp-3);
    }
    .security h2,
    .security p {
      margin: 0;
    }
    .security__badge {
      border-radius: var(--radius-pill);
      background: var(--surface2);
      padding: var(--sp-2) var(--sp-3);
    }
  `,
})
export class AccountSecurityComponent {
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly changeDetector = inject(ChangeDetectorRef);
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly profile = inject(ProfileService);
  private readonly session = inject(SessionService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly ownerId = this.auth.user()?.id;
  private readonly ownerVersion = this.session.version;
  protected readonly passwordForm = this.fb.nonNullable.group({
    currentPassword: ['', [Validators.required, passwordBytes]],
    password: ['', newPasswordValidators],
  });
  protected readonly emailForm = this.fb.nonNullable.group({
    currentPassword: ['', [Validators.required, passwordBytes]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
  });
  protected readonly mode = signal('');
  protected readonly pending = signal(false);
  protected readonly message = signal('');
  protected readonly error = signal('');
  constructor() {
    this.session.changes.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.cancel();
      this.pending.set(false);
      this.error.set('');
      this.message.set('');
    });
    this.destroyRef.onDestroy(() => {
      this.passwordForm.reset();
      this.emailForm.reset();
    });
  }
  private ownsSession(): boolean {
    return this.auth.user()?.id === this.ownerId && this.session.version === this.ownerVersion;
  }
  cancel(): void {
    this.passwordForm.reset();
    this.emailForm.reset();
    this.mode.set('');
  }
  focusTarget(id: string): void {
    if (this.ownsSession())
      this.element.nativeElement.querySelector<HTMLElement>('#' + id)?.focus();
  }
  changePassword(): void {
    if (!this.ownsSession() || this.pending()) return;
    if (this.passwordForm.invalid) {
      this.passwordForm.markAllAsTouched();
      this.changeDetector.detectChanges();
      this.focusTarget(
        this.passwordForm.controls.currentPassword.invalid
          ? this.passwordForm.controls.password.invalid
            ? 'password-errors'
            : 'security-current-password'
          : 'security-new-password',
      );
      return;
    }
    this.pending.set(true);
    this.error.set('');
    this.message.set('');
    this.profile
      .changePassword(this.passwordForm.getRawValue())
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          if (!this.ownsSession()) return;
          this.passwordForm.reset();
          this.auth.clearSession();
          this.router.navigate(['/login'], { queryParams: { reason: 'password-changed' } });
        },
        error: (err) =>
          this.failure(
            err,
            'Password could not be changed. Check your current password and try again.',
          ),
      });
  }
  changeEmail(): void {
    if (!this.ownsSession() || this.pending()) return;
    if (this.emailForm.invalid) {
      this.emailForm.markAllAsTouched();
      this.changeDetector.detectChanges();
      this.focusTarget(
        this.emailForm.controls.currentPassword.invalid
          ? this.emailForm.controls.email.invalid
            ? 'email-errors'
            : 'email-current-password'
          : 'security-new-email',
      );
      return;
    }
    const { currentPassword, email } = this.emailForm.getRawValue();
    this.pending.set(true);
    this.error.set('');
    this.message.set('');
    this.profile
      .requestEmailChange({ currentPassword, email: email.trim() })
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          if (!this.ownsSession()) return;
          this.pending.set(false);
          this.cancel();
          this.message.set(
            `A confirmation link may arrive at ${email.trim()}; your current email remains your login until confirmation. Retry or contact support if it does not arrive.`,
          );
        },
        error: (err) =>
          this.failure(
            err,
            err.status === 409
              ? 'This email is already in use. Choose another email.'
              : 'Email change could not be requested. Check your current password and try again.',
          ),
      });
  }
  private failure(err: { status?: number }, fallback: string): void {
    if (!this.ownsSession()) return;
    this.pending.set(false);
    this.error.set(
      err.status === 429
        ? 'Too many attempts. Please wait before trying again.'
        : err.status === 503
          ? 'Email is temporarily unavailable. Please try again later.'
          : fallback,
    );
  }
  requestVerification(): void {
    if (!this.ownsSession() || this.pending()) return;
    this.pending.set(true);
    this.error.set('');
    this.message.set('');
    this.profile
      .requestEmailVerification()
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.pending.set(false);
          this.message.set(
            'A verification link may arrive; retry or contact support if it does not.',
          );
        },
        error: (err) => this.failure(err, 'Unable to request a verification link. Try again.'),
      });
  }
}
