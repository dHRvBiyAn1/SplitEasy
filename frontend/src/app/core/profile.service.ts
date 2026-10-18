import { Injectable, inject, linkedSignal } from '@angular/core';
import { AuthService } from './auth/auth.service';

export type Currency = 'USD' | 'EUR' | 'GBP' | 'INR';

export const CURRENCY_SYMBOL: Record<Currency, string> = {
  USD: '$',
  EUR: '€',
  GBP: '£',
  INR: '₹',
};

/** Avatar swatches (from the profile mock) — reused for the profile + sidebar avatar. */
export const AVATAR_COLORS = ['#12A57E', '#D96D3F', '#3F7BD9', '#C25B84', '#D9A03F'];

interface ProfilePrefs {
  phone: string;
  currency: Currency;
  avatarColor: string;
  joinedAt: string; // ISO — first time the profile was initialised on this device
}

const KEY = 'spliteasy.profile';

/**
 * Client-side profile preferences the backend doesn't model yet (phone, display currency, avatar
 * color). Persisted per immutable account ID to localStorage; name/email live on the auth user.
 * Preferences remain client-side only.
 */
@Injectable({ providedIn: 'root' })
export class ProfileService {
  private readonly auth = inject(AuthService);
  // Reset on identity changes before any read or write, without waiting for an effect.
  private readonly _prefs = linkedSignal({
    source: () => this.auth.user()?.id ?? null,
    computation: (userId) => this.read(userId),
  });
  readonly prefs = this._prefs.asReadonly();

  constructor() {
    // The legacy key has no owner: migrating its personal fields could expose another account.
    localStorage.removeItem(KEY);
  }

  update(patch: Partial<ProfilePrefs>): void {
    const userId = this.auth.user()?.id;
    if (!userId) return;
    const next = { ...this._prefs(), ...patch };
    this._prefs.set(next);
    localStorage.setItem(`${KEY}.${userId}`, JSON.stringify(next));
  }

  private read(userId: string | null): ProfilePrefs {
    const defaults: ProfilePrefs = {
      phone: '',
      currency: 'USD',
      avatarColor: AVATAR_COLORS[0],
      joinedAt: new Date().toISOString(),
    };
    if (!userId) return defaults;
    const key = `${KEY}.${userId}`;
    try {
      const raw = localStorage.getItem(key);
      if (raw) {
        return { ...defaults, ...(JSON.parse(raw) as Partial<ProfilePrefs>) };
      }
    } catch {
      // fall through to defaults
    }
    localStorage.setItem(key, JSON.stringify(defaults));
    return defaults;
  }
}
