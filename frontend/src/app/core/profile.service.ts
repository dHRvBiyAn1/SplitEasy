import { Injectable, signal } from '@angular/core';

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
const DEFAULTS: ProfilePrefs = {
  phone: '',
  currency: 'USD',
  avatarColor: AVATAR_COLORS[0],
  joinedAt: new Date().toISOString(),
};

/**
 * Client-side profile preferences the backend doesn't model yet (phone, display currency, avatar
 * color). Persisted to localStorage; name/email live on the auth user. Backend persistence is a
 * follow-up — see PROFILE note in the PR.
 */
@Injectable({ providedIn: 'root' })
export class ProfileService {
  private readonly _prefs = signal<ProfilePrefs>(this.read());
  readonly prefs = this._prefs.asReadonly();

  update(patch: Partial<ProfilePrefs>): void {
    const next = { ...this._prefs(), ...patch };
    this._prefs.set(next);
    localStorage.setItem(KEY, JSON.stringify(next));
  }

  private read(): ProfilePrefs {
    try {
      const raw = localStorage.getItem(KEY);
      if (raw) {
        return { ...DEFAULTS, ...(JSON.parse(raw) as Partial<ProfilePrefs>) };
      }
    } catch {
      // fall through to defaults
    }
    localStorage.setItem(KEY, JSON.stringify(DEFAULTS));
    return DEFAULTS;
  }
}
