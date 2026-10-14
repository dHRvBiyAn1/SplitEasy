import { apiPath } from './api.service';

describe('API request boundary', () => {
  it.each(['/api', 'https://api.split-easy.io/api'])(
    'recognizes only routes under the configured API: %s',
    (base) => {
      expect(apiPath(`${base}/groups?limit=10`, base)).toBe('/groups');
      expect(apiPath(`${base}/auth/login`, base)).toBe('/auth/login');
      expect(apiPath(`${base}/auth/register`, base)).toBe('/auth/register');
      expect(apiPath(`${base}-admin`, base)).toBeNull();
      expect(apiPath(`${base}/../outside`, base)).toBeNull();
      expect(apiPath(`${base}/%2e%2e/outside`, base)).toBeNull();
      expect(apiPath('https://untrusted.example/api/groups', base)).toBeNull();
      expect(apiPath('https://api.split-easy.io.evil/api/groups', base)).toBeNull();
      expect(apiPath('https://api.split-easy.io@evil.example/api/groups', base)).toBeNull();
      expect(apiPath('https://user:password@api.split-easy.io/api/groups', base)).toBeNull();
    },
  );
});
