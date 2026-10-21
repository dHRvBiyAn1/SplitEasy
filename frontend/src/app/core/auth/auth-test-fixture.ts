import { UserSummary } from './auth.models';

/** JWT-shaped fixture for controlled HTTP tests; the server still validates real signatures. */
export function testJwt(id: string): string {
  return `eyJhbGciOiJIUzI1NiJ9.${btoa(JSON.stringify({ sub: id }))
    .replace(/=/g, '')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')}.dGVzdA`;
}
export function storeTestSession(user: UserSummary): string {
  const token = testJwt(user.id);
  localStorage.setItem('spliteasy.accessToken', token);
  localStorage.setItem('spliteasy.user', JSON.stringify(user));
  return token;
}
