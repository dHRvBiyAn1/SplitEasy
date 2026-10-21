import { DestroyRef, Injectable, computed, inject, signal, untracked } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { ApiService } from '../api/api.service';
import { AuthResponse, LoginRequest, RegisterRequest, UserSummary } from './auth.models';
import { SessionService } from './session.service';

const TOKEN_KEY = 'spliteasy.accessToken';
const USER_KEY = 'spliteasy.user';
interface StoredSession {
  token: string;
  user: UserSummary;
}

/**
 * Owns authentication state: calls the auth endpoints, persists the access token
 * and current user in localStorage (so a refresh keeps you logged in), and exposes
 * the current user as a signal. The token is attached to requests by authInterceptor.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);

  private readonly currentSession = signal<StoredSession | null>(this.readStoredSession());
  readonly user = computed(() => this.currentSession()?.user ?? null);
  readonly isAuthenticated = computed(() => this.user() !== null);

  constructor() {
    const synchronize = (event: StorageEvent) => {
      if (
        event.storageArea !== localStorage ||
        (event.key !== null && event.key !== TOKEN_KEY && event.key !== USER_KEY)
      )
        return;
      const next = this.readStoredSession();
      const previous = this.currentSession();
      if (previous?.token !== next?.token || previous?.user.id !== next?.user.id)
        this.session.reset();
      this.currentSession.set(next);
    };
    window.addEventListener('storage', synchronize);
    inject(DestroyRef).onDestroy(() => window.removeEventListener('storage', synchronize));
  }

  register(request: RegisterRequest): Observable<AuthResponse> {
    return this.api
      .post<AuthResponse>('/auth/register', request)
      .pipe(tap((res) => this.persist(res)));
  }

  login(request: LoginRequest): Observable<AuthResponse> {
    return this.api
      .post<AuthResponse>('/auth/login', request)
      .pipe(tap((res) => this.persist(res)));
  }

  logout(): void {
    const current = this.currentSession();
    this.session.reset();
    // An unseen login in another tab owns its stored credentials, even before its storage event.
    if (
      current &&
      localStorage.getItem(TOKEN_KEY) === current.token &&
      this.readStoredUser()?.id === current.user.id
    ) {
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(USER_KEY);
    }
    this.currentSession.set(null);
  }

  getToken(): string | null {
    return untracked(this.currentSession)?.token ?? null;
  }

  /** Fresh profile data may change presentation, never identity, email or the access token. */
  applyProfileName(id: string, version: number, displayName: string): void {
    const current = this.currentSession();
    const user = current?.user;
    if (!user || user.id !== id || this.session.version !== version) return;
    const next = { ...user, displayName };
    this.currentSession.set({ token: current!.token, user: next });
    if (localStorage.getItem(TOKEN_KEY) === current!.token && this.readStoredUser()?.id === id)
      localStorage.setItem(USER_KEY, JSON.stringify(next));
  }

  private persist(res: AuthResponse): void {
    this.session.reset();
    localStorage.setItem(TOKEN_KEY, res.accessToken);
    localStorage.setItem(USER_KEY, JSON.stringify(res.user));
    this.currentSession.set({ token: res.accessToken, user: res.user });
  }

  private readStoredSession(): StoredSession | null {
    const token = localStorage.getItem(TOKEN_KEY);
    const user = this.readStoredUser();
    if (!token || !user) return null;
    try {
      const parts = token.split('.');
      if (parts.length !== 3) return null;
      const claims = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')));
      // Presentation-pair coherence only: API signature/issuer/expiry validation remains authoritative.
      return claims.sub === user.id ? { token, user } : null;
    } catch {
      return null;
    }
  }

  private readStoredUser(): UserSummary | null {
    const raw = localStorage.getItem(USER_KEY);
    if (!raw) {
      return null;
    }
    try {
      const user = JSON.parse(raw) as UserSummary;
      return user &&
        typeof user.id === 'string' &&
        !!user.id &&
        typeof user.email === 'string' &&
        typeof user.displayName === 'string'
        ? user
        : null;
    } catch {
      return null;
    }
  }
}
