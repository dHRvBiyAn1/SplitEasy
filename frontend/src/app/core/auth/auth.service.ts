import { DestroyRef, Injectable, computed, inject, signal, untracked } from '@angular/core';
import {
  Observable,
  catchError,
  defer,
  finalize,
  firstValueFrom,
  from,
  of,
  shareReplay,
  tap,
  throwError,
} from 'rxjs';
import { ApiService } from '../api/api.service';
import { AuthResponse, LoginRequest, RegisterRequest, UserSummary } from './auth.models';
import { SessionService } from './session.service';

const AUTH_MARKER = 'spliteasy.auth-change';

interface BrowserSession {
  token: string;
  sessionId: string;
  user: UserSummary;
}

/** Access credentials stay in memory; only the API's HttpOnly cookie survives a reload. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);
  private authAttempt = 0;
  private ownerMarker = this.readMarker();
  private renewal: Observable<AuthResponse> | null = null;
  private readonly currentSession = signal<BrowserSession | null>(null);
  readonly user = computed(() => this.currentSession()?.user ?? null);
  readonly isAuthenticated = computed(() => this.user() !== null);
  readonly logoutWarning = signal<string | null>(null);

  constructor() {
    try {
      localStorage.removeItem('spliteasy.accessToken');
      localStorage.removeItem('spliteasy.user');
    } catch {
      /* Legacy storage is never an authentication source. */
    }
    const synchronize = (event: StorageEvent) => {
      if (event.storageArea !== localStorage || (event.key !== null && event.key !== AUTH_MARKER))
        return;
      const next = this.readMarker();
      if (next === this.ownerMarker) return;
      this.ownerMarker = next;
      this.clearSession(false);
      if (!this.signedOut(next)) {
        const attempt = this.authAttempt;
        this.restore().subscribe({
          error: () => {
            if (attempt === this.authAttempt) this.clearSession(false);
          },
        });
      }
    };
    window.addEventListener('storage', synchronize);
    inject(DestroyRef).onDestroy(() => window.removeEventListener('storage', synchronize));
  }

  initialize(): Observable<AuthResponse | null> {
    if (this.signedOut(this.readMarker())) return of(null);
    return this.restore().pipe(catchError(() => of(null)));
  }

  restore(): Observable<AuthResponse> {
    if (this.renewal) return this.renewal;
    const renewal = this.cookieRequest('/auth/refresh', {}, false).pipe(
      finalize(() => {
        if (this.renewal === renewal) this.renewal = null;
      }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    this.renewal = renewal;
    return renewal;
  }

  register(request: RegisterRequest): Observable<AuthResponse> {
    return this.cookieRequest('/auth/register', request, true);
  }

  login(request: LoginRequest): Observable<AuthResponse> {
    return this.cookieRequest('/auth/login', request, true);
  }

  private cookieRequest(path: string, body: unknown, login: boolean): Observable<AuthResponse> {
    return defer(() => {
      const attempt = login ? ++this.authAttempt : this.authAttempt;
      const marker = this.readMarker();
      const execute = () => {
        if (attempt !== this.authAttempt || marker !== this.readMarker())
          return throwError(
            () => new Error('The authentication request belongs to an earlier session'),
          );
        return this.api
          .post<AuthResponse>(path, body, {
            withCredentials: true,
            headers: path === '/auth/refresh' ? { 'X-SplitEasy-Session': '1' } : undefined,
          })
          .pipe(
            tap((response) => {
              if (attempt !== this.authAttempt || marker !== this.readMarker()) {
                if (login)
                  this.api
                    .post<void>(
                      '/auth/logout',
                      {},
                      { headers: { Authorization: `Bearer ${response.accessToken}` } },
                    )
                    .subscribe({ error: () => {} });
                throw new Error('The authentication request belongs to an earlier session');
              }
              this.accept(response, login);
            }),
          );
      };
      // Keep ownership through body processing: Set-Cookie is already applied when HttpClient emits.
      return navigator.locks
        ? from(
            Promise.resolve(
              navigator.locks.request('spliteasy-auth-cookie', () => firstValueFrom(execute())),
            ),
          )
        : execute();
    });
  }

  requestPasswordReset(email: string): Observable<void> {
    return this.api.post<void>('/auth/password-reset-requests', { email });
  }

  resetPassword(token: string, password: string): Observable<void> {
    return this.api.post<void>('/auth/password-resets', { token, password });
  }

  verifyEmail(token: string): Observable<void> {
    return this.api.post<void>('/auth/email-verifications', { token });
  }

  confirmEmailChange(token: string): Observable<void> {
    return this.api.post<void>('/auth/email-change-confirmations', { token });
  }

  logout(): void {
    this.signOut('/auth/logout', 'copied credentials');
  }

  logoutAll(): void {
    this.signOut('/me/sessions/logout-all', 'other devices and copied credentials');
  }

  private signOut(path: string, unconfirmed: string): void {
    const token = this.getToken();
    this.logoutWarning.set(null);
    this.clearSession();
    const version = this.session.version;
    if (token)
      this.api.post<void>(path, {}, { headers: { Authorization: `Bearer ${token}` } }).subscribe({
        error: (error) => {
          if (error.status !== 401 && this.session.version === version && !this.user())
            this.logoutWarning.set(
              `Signed out on this device. Server sign-out could not be confirmed; ${unconfirmed} may still work. Sign in again to manage your sessions.`,
            );
        },
      });
  }

  clearSession(broadcast = true): void {
    this.authAttempt++;
    this.renewal = null;
    this.session.reset();
    this.currentSession.set(null);
    if (broadcast && this.ownerMarker === this.readMarker()) this.publishMarker(true);
  }

  getToken(): string | null {
    return untracked(this.currentSession)?.token ?? null;
  }

  getSessionId(): string | null {
    return untracked(this.currentSession)?.sessionId ?? null;
  }

  /** Fresh profile data may change presentation, never identity, email or credentials. */
  applyProfileName(id: string, version: number, displayName: string): void {
    const current = this.currentSession();
    if (!current || current.user.id !== id || this.session.version !== version) return;
    this.currentSession.set({ ...current, user: { ...current.user, displayName } });
  }

  private accept(response: AuthResponse, login: boolean): void {
    if (!response.sessionId || !response.accessToken || !response.user?.id)
      throw new Error('Invalid session response');
    const current = this.currentSession();
    if (
      login ||
      (current &&
        (current.sessionId !== response.sessionId || current.user.id !== response.user.id))
    )
      this.session.reset();
    this.logoutWarning.set(null);
    this.currentSession.set({
      token: response.accessToken,
      sessionId: response.sessionId,
      user: response.user,
    });
    if (login) this.publishMarker(false);
    else this.ownerMarker = this.readMarker();
  }

  private readMarker(): string | null {
    try {
      return localStorage.getItem(AUTH_MARKER);
    } catch {
      return null;
    }
  }

  private signedOut(marker: string | null): boolean {
    try {
      return marker !== null && JSON.parse(marker).signedOut === true;
    } catch {
      return false;
    }
  }

  private publishMarker(signedOut: boolean): void {
    const marker = JSON.stringify({ id: crypto.randomUUID(), signedOut });
    try {
      localStorage.setItem(AUTH_MARKER, marker);
      this.ownerMarker = marker;
    } catch {
      /* Authentication stays in memory when browser storage is unavailable. */
    }
  }
}
