import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { ProfileService } from '../core/profile.service';
import { ProfileComponent } from './profile.component';

describe('truthful profile preferences', () => {
  it('saves account name and preferences through HTTP while keeping email read-only', () => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    TestBed.inject(AuthService)
      .login({ email: 'alice@example.com', password: 'password' })
      .subscribe();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/auth/login').flush({
      accessToken: 'token',
      user: { id: 'alice', displayName: 'Alice', email: 'alice@example.com' },
    });
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    http.expectOne('/api/me').flush({
      id: 'alice',
      displayName: 'Fresh Alice',
      email: 'alice@example.com',
      phone: '',
      preferredCurrency: 'USD',
      avatarColor: '#12A57E',
      createdAt: '2020-02-01T00:00:00Z',
    });
    fixture.detectChanges();
    const edit = fixture.nativeElement.querySelector('.pcard__edit') as HTMLButtonElement;
    expect(edit.textContent).toContain('Edit profile');
    expect(fixture.nativeElement.textContent).toContain('Member since February 2020');
    edit.click();
    fixture.detectChanges();
    const name = fixture.nativeElement.querySelector(
      'input[autocomplete=name]',
    ) as HTMLInputElement;
    expect(name.value).toBe('Fresh Alice');
    name.value = 'Alice Updated';
    name.dispatchEvent(new Event('input'));
    expect(fixture.nativeElement.querySelector('input[type=email]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('alice@example.com');
    expect(fixture.nativeElement.textContent).toContain(
      'Currency preferences do not change or convert recorded amounts.',
    );
    fixture.nativeElement.querySelector('button[type=submit]').click();
    const save = http.expectOne('/api/me');
    expect(save.request.method).toBe('PATCH');
    expect(save.request.body.displayName).toBe('Alice Updated');
    expect(save.request.body.email).toBeUndefined();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('button[type=submit]').disabled).toBe(true);
    save.flush({
      id: 'alice',
      displayName: 'Alice Updated',
      email: 'alice@example.com',
      phone: '',
      preferredCurrency: 'USD',
      avatarColor: '#12A57E',
      createdAt: '2020-02-01T00:00:00Z',
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(TestBed.inject(AuthService).user()).toEqual({
      id: 'alice',
      displayName: 'Alice Updated',
      email: 'alice@example.com',
    });
    expect(TestBed.inject(AuthService).getToken()).toBe('token');
    http.verify();
  });
});

describe('server profile request ownership and errors', () => {
  let http: HttpTestingController;
  let auth: AuthService;
  const alice = { id: 'alice', displayName: 'Alice', email: 'alice@example.com' };
  const bob = { id: 'bob', displayName: 'Bob', email: 'bob@example.com' };
  const profile = (user = alice, phone = '') => ({
    ...user,
    phone,
    preferredCurrency: 'USD',
    avatarColor: '#12A57E',
    createdAt: '2020-02-01T00:00:00Z',
  });
  function login(user = alice): void {
    auth.login({ email: user.email, password: 'password' }).subscribe();
    http.expectOne('/api/auth/login').flush({ accessToken: `${user.id}-token`, user });
  }
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    login();
  });
  afterEach(() => http.verify());

  it('renders accepted server email in view and edit modes without allowing an email mutation', () => {
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    const fresh = { ...profile(), email: 'current@example.net' };
    http.expectOne('/api/me').flush(fresh);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('dl').textContent).toContain('current@example.net');
    expect(fixture.nativeElement.querySelector('dl').textContent).not.toContain(
      'alice@example.com',
    );
    fixture.nativeElement.querySelector('.pcard__edit').click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('form').textContent).toContain(
      'current@example.net',
    );
    expect(fixture.nativeElement.querySelector('input[type=email]')).toBeNull();
    fixture.nativeElement.querySelector('button[type=submit]').click();
    const save = http.expectOne('/api/me');
    expect(save.request.body.email).toBeUndefined();
    save.flush(fresh);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('dl').textContent).toContain('current@example.net');
    expect(auth.user()?.email).toBe('alice@example.com');
  });

  it('shows refresh failures and retries; failed saves retain the name draft until explicit retry', () => {
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Loading profile');
    http.expectOne('/api/me').flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain(
      'Profile unavailable',
    );
    fixture.componentInstance.refresh();
    http.expectOne('/api/me').flush(profile());
    fixture.detectChanges();
    fixture.componentInstance.startEdit();
    fixture.detectChanges();
    const name = fixture.nativeElement.querySelector(
      'input[autocomplete=name]',
    ) as HTMLInputElement;
    name.value = 'Alice Draft';
    name.dispatchEvent(new Event('input'));
    fixture.componentInstance.save();
    http.expectOne('/api/me').flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain(
      'could not be saved',
    );
    expect(name.value).toBe('Alice Draft');
    expect(auth.user()?.displayName).toBe('Alice');
    fixture.componentInstance.save();
    http.expectOne('/api/me').flush({ ...profile(), displayName: 'Alice Draft' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(auth.user()?.displayName).toBe('Alice Draft');
  });

  for (const next of [bob, alice]) {
    it(`cancels an old GET and PATCH and closes drafts when ${next.id} logs in again`, () => {
      const fixture = TestBed.createComponent(ProfileComponent);
      fixture.detectChanges();
      const oldRead = http.expectOne('/api/me');
      login(next);
      // Read before any effect/render: no old account state may survive.
      expect(TestBed.inject(ProfileService).data()).toBeNull();
      expect(oldRead.cancelled).toBe(true);
      fixture.detectChanges();
      http.expectOne('/api/me').flush(profile(next));
      fixture.componentInstance.startEdit();
      fixture.componentInstance.save();
      const oldSave = http.expectOne('/api/me');
      login(next);
      expect(oldSave.cancelled).toBe(true);
      fixture.componentInstance.save();
      http.expectNone('/api/me');
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('form')).toBeNull();
      expect(fixture.nativeElement.textContent).not.toContain('Alice private phone');
      http.expectOne('/api/me').flush(profile(next, 'Fresh phone'));
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Fresh phone');
      expect(auth.user()).toEqual(next);
    });
  }

  it('uses the latest refresh response and ignores old generation success or failure', () => {
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    const initial = http.expectOne('/api/me');
    fixture.componentInstance.refresh();
    http
      .expectOne('/api/me')
      .flush({ ...profile(), displayName: 'Fresh name', phone: 'Fresh phone' });
    initial.flush({ ...profile(), displayName: 'Old name', phone: 'Old phone' });
    fixture.detectChanges();
    expect(auth.user()?.displayName).toBe('Fresh name');
    expect(fixture.nativeElement.textContent).toContain('Fresh phone');
    fixture.componentInstance.refresh();
    const old = http.expectOne('/api/me');
    fixture.componentInstance.refresh();
    http.expectOne('/api/me').flush(profile(alice, 'Newest phone'));
    old.flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=alert]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Newest phone');
  });

  it('offers only owned legacy preferences, preserves them on cancel, and imports only on explicit choice', () => {
    const legacy = {
      phone: 'Alice private phone',
      currency: 'INR',
      avatarColor: '#3F7BD9',
      joinedAt: '1999-01-01',
    };
    localStorage.setItem('spliteasy.profile.alice', JSON.stringify(legacy));
    localStorage.setItem(
      'spliteasy.profile.bob',
      JSON.stringify({ ...legacy, phone: 'Bob private phone' }),
    );
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    http.expectOne('/api/me').flush(profile());
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Import device preferences');
    expect(fixture.nativeElement.textContent).not.toContain('Alice private phone');
    const keep = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find((button) => button.textContent?.includes('Keep server'))!;
    keep.click();
    fixture.detectChanges();
    http.expectNone('/api/me');
    expect(localStorage.getItem('spliteasy.profile.alice')).toBe(JSON.stringify(legacy));
    expect(fixture.nativeElement.textContent).not.toContain('Import device preferences');
    login();
    fixture.detectChanges();
    http.expectOne('/api/me').flush(profile());
    fixture.detectChanges();
    const importButton = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find((button) => button.textContent?.includes('Import device'))!;
    importButton.click();
    const request = http.expectOne('/api/me');
    expect(request.request.body).toEqual({
      phone: 'Alice private phone',
      avatarColor: '#3F7BD9',
      preferredCurrency: 'INR',
    });
    request.flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain(
      'could not be saved',
    );
    expect(localStorage.getItem('spliteasy.profile.alice')).toBe(JSON.stringify(legacy));
    importButton.click();
    const retry = http.expectOne('/api/me');
    expect(retry.request.body).toEqual(request.request.body);
    retry.flush({
      ...profile(),
      phone: legacy.phone,
      avatarColor: legacy.avatarColor,
      preferredCurrency: legacy.currency,
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Alice private phone');
    expect(fixture.nativeElement.textContent).toContain('Member since February 2020');
    expect(localStorage.getItem('spliteasy.profile.alice')).toBeNull();
    expect(localStorage.getItem('spliteasy.profile.bob')).not.toBeNull();
    fixture.destroy();
    const reopened = TestBed.createComponent(ProfileComponent);
    reopened.detectChanges();
    expect(reopened.nativeElement.textContent).toContain('Alice private phone');
    expect(reopened.nativeElement.textContent).not.toContain('Import device preferences');
    http.expectNone('/api/me');
  });
});
