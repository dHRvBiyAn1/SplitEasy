import { AppearancePanelComponent } from '../shared/appearance-panel.component';
import { Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { ProfileService, Currency, CURRENCY_SYMBOL, AVATAR_COLORS } from '../core/profile.service';

@Component({
  selector: 'app-profile',
  imports: [ReactiveFormsModule, AppearancePanelComponent],
  templateUrl: './profile.component.html',
  styleUrl: './profile.component.scss',
})
export class ProfileComponent {
  private readonly auth = inject(AuthService);
  private readonly profile = inject(ProfileService);
  private readonly router = inject(Router);

  protected readonly user = this.auth.user;
  protected readonly prefs = this.profile.prefs;
  protected readonly editing = linkedSignal({
    source: () => this.user()?.id,
    computation: () => false,
  });

  protected readonly currencies: Currency[] = ['USD', 'EUR', 'GBP', 'INR'];
  protected readonly avatarColors = AVATAR_COLORS;

  protected readonly phone = new FormControl('', { nonNullable: true });
  protected readonly currency = signal<Currency>('USD');
  protected readonly avatarColor = signal<string>(AVATAR_COLORS[0]);

  protected readonly initials = computed(() =>
    (this.user()?.displayName ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]?.toUpperCase() ?? '')
      .join(''),
  );

  /** Avatar tint — previews the picked color while editing, otherwise the saved one. */
  protected readonly avatarStyle = computed(() => {
    const c = this.editing() ? this.avatarColor() : this.prefs().avatarColor;
    return {
      background: `color-mix(in oklab, ${c} 24%, var(--surface))`,
      color: `color-mix(in oklab, ${c}, var(--avatar-ink) 30%)`,
    };
  });

  memberSince(): string {
    return new Date(this.prefs().joinedAt).toLocaleDateString('en-US', {
      month: 'long',
      year: 'numeric',
    });
  }

  currencyLabel(c: Currency): string {
    return `${c} (${CURRENCY_SYMBOL[c]})`;
  }

  startEdit(): void {
    this.phone.setValue(this.prefs().phone);
    this.currency.set(this.prefs().currency);
    this.avatarColor.set(this.prefs().avatarColor);
    this.editing.set(true);
  }

  cancel(): void {
    this.editing.set(false);
  }

  save(): void {
    if (!this.editing()) return;
    this.profile.update({
      phone: this.phone.value.trim(),
      currency: this.currency(),
      avatarColor: this.avatarColor(),
    });
    this.editing.set(false);
  }

  signOut(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
