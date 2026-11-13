import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';
import { activeLocale } from '../core/i18n/i18n.service';
import { ProfileComponent } from './profile.component';

const me = (locale: string) => ({
  id: 'alice',
  displayName: 'Alice',
  email: 'alice@example.com',
  phone: '',
  preferredCurrency: 'USD',
  avatarColor: '#12A57E',
  locale,
  createdAt: '2020-02-01T00:00:00Z',
});

describe('profile language', () => {
  let http: HttpTestingController;
  let fixture: ReturnType<typeof TestBed.createComponent<ProfileComponent>>;
  const text = () => fixture.nativeElement.textContent as string;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    TestBed.inject(AuthService).login({ email: 'a@example.com', password: 'password' }).subscribe();
    http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/auth/login').flush({
      accessToken: 'token',
      sessionId: 'alice-device',
      user: { id: 'alice', displayName: 'Alice', email: 'alice@example.com' },
    });
    fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
  });
  afterEach(() => activeLocale.set('en'));

  it('adopts the server preference after sign-in, switches instantly on save and resets on sign-out', () => {
    http.expectOne('/api/me').flush(me('es'));
    http.expectOne('/i18n/es.json').flush({ 'profile.editProfile': 'Editar perfil' });
    fixture.detectChanges();
    expect(text()).toContain('Editar perfil');
    expect(document.documentElement.lang).toBe('es');

    const select = fixture.nativeElement.querySelector('select#language') as HTMLSelectElement;
    expect([...select.options].map((o) => o.value)).toEqual(['en', 'hi', 'es', 'fr']);
    expect(select.value).toBe('es');
    select.value = 'fr';
    select.dispatchEvent(new Event('change'));
    const save = http.expectOne('/api/me');
    expect(save.request.method).toBe('PATCH');
    expect(save.request.body).toEqual({ locale: 'fr' });
    http.expectOne('/i18n/fr.json').flush({
      'profile.editProfile': 'Modifier le profil',
      'appearancePanel.light': 'Clair',
    });
    fixture.detectChanges();
    expect(text()).toContain('Modifier le profil'); // switched before the PATCH answered
    expect(text()).toContain('Clair'); // option labels built in class fields follow the switch too
    save.flush(me('fr'));

    TestBed.inject(SessionService).reset();
    fixture.detectChanges();
    expect(text()).toContain('Edit profile');
    expect(document.documentElement.lang).toBe('en');
    http.match('/api/me'); // the account reloads its profile after a session reset
    http.verify();
  });
});
