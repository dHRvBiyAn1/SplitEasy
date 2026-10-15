import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { API_BASE_URL, apiPath } from '../api/api.service';
import { AuthService } from './auth.service';
import { SessionService } from './session.service';

/**
 * Attaches the bearer token to outgoing API calls and, on a 401, clears the
 * session and bounces to /login. Fulfils the AGENTS.md "shared HTTP interceptor
 * for auth token + error handling" convention. Auth endpoints are left untouched.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const session = inject(SessionService);
  const version = session.version;

  const token = auth.getToken();
  const path = apiPath(req.url, inject(API_BASE_URL));
  const isPrivateApi = path !== null && !path.startsWith('/auth/');
  const authorized =
    token && isPrivateApi ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(authorized).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401 && isPrivateApi && session.version === version) {
        auth.logout();
        router.navigate(['/login']);
      }
      return throwError(() => error);
    }),
  );
};
