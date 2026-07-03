import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * Attaches the bearer token to outgoing API calls and, on a 401, clears the
 * session and bounces to /login. Fulfils the AGENTS.md "shared HTTP interceptor
 * for auth token + error handling" convention. Auth endpoints are left untouched.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  const token = auth.getToken();
  const isAuthCall = req.url.includes('/api/auth/');
  const authorized =
    token && !isAuthCall
      ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : req;

  return next(authorized).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401 && !isAuthCall) {
        auth.logout();
        router.navigate(['/login']);
      }
      return throwError(() => error);
    }),
  );
};
