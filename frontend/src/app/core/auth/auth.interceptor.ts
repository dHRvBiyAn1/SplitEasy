import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, of, switchMap, throwError } from 'rxjs';
import { API_BASE_URL, apiPath } from '../api/api.service';
import { AuthService } from './auth.service';
import { SessionService } from './session.service';

/** Attach access credentials only to our API; a renewal may retry only its original owner. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const session = inject(SessionService);
  const version = session.version;
  const sessionId = auth.getSessionId();
  const userId = auth.user()?.id;
  const token = auth.getToken();
  const path = apiPath(req.url, inject(API_BASE_URL));
  const isPrivateApi = path !== null && !path.startsWith('/auth/');
  const ownsRequest = () =>
    session.version === version && auth.getSessionId() === sessionId && auth.user()?.id === userId;
  const expired = () => {
    auth.clearSession();
    void router.navigate(['/login']);
  };
  const authorized =
    token && isPrivateApi && !req.headers.has('Authorization')
      ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : req;

  return next(authorized).pipe(
    catchError((error: HttpErrorResponse) => {
      if (
        error.status !== 401 ||
        !isPrivateApi ||
        path === '/me/sessions/logout-all' ||
        !ownsRequest()
      )
        return throwError(() => error);
      if (!token) {
        expired();
        return throwError(() => error);
      }
      // A deliberately captured bearer must never be replaced by a different current identity.
      if (authorized.headers.get('Authorization') !== `Bearer ${token}`)
        return throwError(() => error);
      const renewal: Observable<unknown> = auth.getToken() !== token ? of(null) : auth.restore();
      return renewal.pipe(
        catchError((failure) => {
          if (ownsRequest()) expired();
          return throwError(() => failure);
        }),
        switchMap(() => {
          if (!ownsRequest()) return throwError(() => error);
          return next(
            req.clone({ setHeaders: { Authorization: `Bearer ${auth.getToken()}` } }),
          ).pipe(
            catchError((failure: HttpErrorResponse) => {
              if (failure.status === 401 && ownsRequest()) expired();
              return throwError(() => failure);
            }),
          );
        }),
      );
    }),
  );
};
