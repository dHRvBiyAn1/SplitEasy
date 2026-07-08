import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Base path all API calls are prefixed with (`/api` is proxied to the backend in dev). */
export const API_BASE = '/api';

/** Auth endpoints (register/login) — the interceptor leaves these untouched (no bearer, no 401 bounce). */
export const AUTH_URL_PREFIX = `${API_BASE}/auth/`;

/**
 * Shared HTTP layer for the SplitEasy backend. All resource services
 * (health, groups, expenses, ...) go through this instead of using
 * HttpClient directly, so base URL and cross-cutting concerns live in
 * one place. `/api` is proxied to the backend in dev (see proxy.conf.json).
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = API_BASE;

  get<T>(path: string): Observable<T> {
    return this.http.get<T>(`${this.baseUrl}${path}`);
  }

  post<T>(path: string, body: unknown): Observable<T> {
    return this.http.post<T>(`${this.baseUrl}${path}`, body);
  }

  put<T>(path: string, body: unknown): Observable<T> {
    return this.http.put<T>(`${this.baseUrl}${path}`, body);
  }

  delete<T>(path: string): Observable<T> {
    return this.http.delete<T>(`${this.baseUrl}${path}`);
  }
}
