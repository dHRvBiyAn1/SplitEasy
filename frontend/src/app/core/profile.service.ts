import {
  DestroyRef,
  Injectable,
  computed,
  effect,
  inject,
  linkedSignal,
  untracked,
} from '@angular/core';
import { Observable, takeUntil } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AuthService } from './auth/auth.service';
import { SessionService } from './auth/session.service';
import { ApiService } from './api/api.service';

export type Currency = 'USD' | 'EUR' | 'GBP' | 'INR';
export const CURRENCY_SYMBOL: Record<Currency, string> = { USD: '$', EUR: '€', GBP: '£', INR: '₹' };
export const AVATAR_COLORS = ['#12A57E', '#D96D3F', '#3F7BD9', '#C25B84', '#D9A03F'];
export interface CurrentProfile {
  id: string;
  email: string;
  emailVerified: boolean;
  displayName: string;
  phone: string;
  avatarColor: string;
  preferredCurrency: Currency;
  createdAt: string;
}
export type ProfilePatch = Partial<
  Pick<CurrentProfile, 'displayName' | 'phone' | 'avatarColor' | 'preferredCurrency'>
>;
interface LegacyPrefs {
  phone: string;
  currency: Currency;
  avatarColor: string;
}
interface State {
  data: CurrentProfile | null;
  loading: boolean;
  saving: boolean;
  error: string;
}
const KEY = 'spliteasy.profile';

/** Server-owned profile. Every request belongs to both an account and a login generation. */
@Injectable({ providedIn: 'root' })
export class ProfileService {
  private readonly destroyRef = inject(DestroyRef);
  private readonly auth = inject(AuthService);
  private readonly session = inject(SessionService);
  private readonly api = inject(ApiService);
  private readonly owner = computed(
    () => `${this.session.currentVersion()}:${this.auth.user()?.id ?? ''}`,
  );
  private generation = 0;
  private readonly state = linkedSignal({
    source: this.owner,
    computation: (): State => ({ data: null, loading: false, saving: false, error: '' }),
  });
  readonly data = computed(() => this.state().data);
  readonly loading = computed(() => this.state().loading);
  readonly saving = computed(() => this.state().saving);
  readonly error = computed(() => this.state().error);
  readonly prefs = computed(() => ({
    phone: this.data()?.phone ?? '',
    currency: this.data()?.preferredCurrency ?? 'USD',
    avatarColor: this.data()?.avatarColor ?? AVATAR_COLORS[0],
    joinedAt: this.data()?.createdAt ?? '',
  }));
  readonly legacyPrefs = computed(() => {
    this.owner();
    return this.readLegacy(this.auth.user()?.id);
  });

  constructor() {
    localStorage.removeItem(KEY); // Ownerless data must never be imported.
    effect(() => {
      this.owner();
      untracked(() => this.refresh());
    });
  }

  refresh(): void {
    if (!this.auth.user()) return;
    this.request('get');
  }

  update(patch: ProfilePatch, onSaved?: () => void): void {
    if (!this.auth.user() || this.saving()) return;
    this.request('patch', patch, onSaved);
  }

  requestEmailVerification(): Observable<void> {
    return this.api.post<void>('/me/email-verification-requests', {});
  }

  changePassword(request: { currentPassword: string; password: string }): Observable<void> {
    return this.api.post<void>('/me/password', request);
  }

  requestEmailChange(request: { currentPassword: string; email: string }): Observable<void> {
    return this.api.post<void>('/me/email-change-requests', request);
  }

  importLegacy(onSaved?: () => void): void {
    const legacy = this.legacyPrefs();
    const id = this.auth.user()?.id;
    if (!legacy || !id) return;
    this.update(
      { phone: legacy.phone, avatarColor: legacy.avatarColor, preferredCurrency: legacy.currency },
      () => {
        localStorage.removeItem(`${KEY}.${id}`);
        onSaved?.();
      },
    );
  }

  private request(method: 'get' | 'patch', patch?: ProfilePatch, onSaved?: () => void): void {
    const owner = this.owner();
    const id = this.auth.user()!.id;
    const version = this.session.version;
    const generation = ++this.generation;
    const current = () => owner === this.owner() && generation === this.generation;
    this.state.update((state) => ({
      ...state,
      loading: method === 'get',
      saving: method === 'patch',
      error: '',
    }));
    const request =
      method === 'get'
        ? this.api.get<CurrentProfile>('/me')
        : this.api.patch<CurrentProfile>('/me', patch);
    request.pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (data) => {
        if (!current() || data.id !== id) return;
        this.state.set({ data, loading: false, saving: false, error: '' });
        this.auth.applyProfileName(id, version, data.displayName);
        onSaved?.();
      },
      error: () => {
        if (!current()) return;
        this.state.update((state) => ({
          ...state,
          loading: false,
          saving: false,
          error:
            method === 'get'
              ? 'Profile unavailable. Try again.'
              : 'Profile could not be saved. Your changes are still here. Try again.',
        }));
      },
    });
  }

  private readLegacy(id: string | undefined): LegacyPrefs | null {
    if (!id) return null;
    try {
      const raw = localStorage.getItem(`${KEY}.${id}`);
      if (!raw) return null;
      const value = JSON.parse(raw) as LegacyPrefs;
      if (
        typeof value.phone !== 'string' ||
        value.phone.length > 50 ||
        !['USD', 'EUR', 'GBP', 'INR'].includes(value.currency) ||
        !AVATAR_COLORS.includes(value.avatarColor)
      )
        return null;
      return { phone: value.phone, currency: value.currency, avatarColor: value.avatarColor };
    } catch {
      return null;
    }
  }
}
