import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthHeroComponent } from './auth-hero.component';
import { AuthService } from '../core/auth/auth.service';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { translate } from '../core/i18n/i18n.service';

@Component({
  selector: 'app-login',
  imports: [TranslatePipe, ReactiveFormsModule, RouterLink, AuthHeroComponent],
  templateUrl: './login.component.html',
  styleUrl: './auth.scss',
})
export class LoginComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  private readonly reason = inject(ActivatedRoute).snapshot.queryParamMap.get('reason');
  protected readonly securityNotice =
    this.reason === 'password-changed'
      ? translate('login.passwordChangedLogIn')
      : this.reason === 'account-closed'
        ? translate('login.yourAccountHasBeen')
        : '';
  protected readonly logoutWarning = this.auth.logoutWarning;
  protected readonly passwordVisible = signal(false);
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required]],
  });

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    if (this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.auth.login(this.form.getRawValue()).subscribe({
      next: () => this.router.navigate(['/dashboard']),
      error: (err) => {
        this.error.set(
          err?.status === 429
            ? translate('accountAction.tooManyAttemptsPlease')
            : (err?.error?.message ?? translate('login.loginFailedPleaseTry')),
        );
        this.submitting.set(false);
      },
    });
  }
}
