import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PendingWritesService } from '../pending-writes.service';
import { API_BASE_URL } from '../api/api.service';
import { AuthService } from './auth.service';
import { AuthResponse, UserSummary } from './auth.models';
/** JWT-shaped fixture for controlled HTTP tests; the server still validates real signatures. */
export function testJwt(id: string): string {
  return `eyJhbGciOiJIUzI1NiJ9.${btoa(JSON.stringify({ sub: id }))
    .replace(/=/g, '')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')}.dGVzdA`;
}
/** A controlled HTTP sign-in; restore=true models a cookie-backed injector reload. */
export function storeTestSession(user: UserSummary, restore = false): string {
  const response = testAuthResponse(user);
  const auth = TestBed.inject(AuthService);
  TestBed.inject(PendingWritesService);
  const request = restore
    ? auth.restore()
    : auth.login({ email: user.email, password: 'fixture-password' });
  request.subscribe();
  TestBed.inject(HttpTestingController)
    .expectOne(TestBed.inject(API_BASE_URL) + '/auth/' + (restore ? 'refresh' : 'login'))
    .flush(response);
  return response.accessToken;
}

export function testAuthResponse(user: UserSummary): AuthResponse {
  return {
    accessToken: testJwt(user.id),
    sessionId: 'session-' + user.id,
    tokenType: 'Bearer',
    expiresIn: 3600,
    user,
  };
}
