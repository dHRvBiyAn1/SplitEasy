import { ApplicationInitStatus } from '@angular/core';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { appConfig } from '../app.config';
import { AuthService } from '../core/auth/auth.service';
import { testAuthResponse } from '../core/auth/auth-test-fixture';
import { InvitationComponent } from './invitation.component';

const viewer = { id: 'viewer', displayName: 'Viewer', email: 'viewer@example.test' };
const token = 'B'.repeat(43);

describe('hard invitation entry with the real cookie initializer', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify({ ignoreCancelled: true });
    TestBed.resetTestingModule();
    localStorage.clear();
    sessionStorage.clear();
    history.replaceState({}, '', '/');
  });

  it('preserves the captured invitation after initial restore while clearing it on actual device replacement', async () => {
    localStorage.clear();
    sessionStorage.clear();
    history.replaceState({}, '', '/invitation#action=group-invitation&token=' + token);
    TestBed.configureTestingModule({
      imports: [InvitationComponent],
      providers: [...appConfig.providers, provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    const initialized = TestBed.inject(ApplicationInitStatus);
    http.expectOne('/api/auth/refresh').flush(testAuthResponse(viewer));
    await initialized.donePromise;
    const fixture = TestBed.createComponent(InvitationComponent);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(location.hash).toBe('');
    expect(root.textContent).toContain(viewer.email);
    expect(
      [...root.querySelectorAll('button')].some(
        (b) => b.textContent?.trim() === 'Preview invitation',
      ),
    ).toBe(true);
    http.expectNone('/api/invitations/preview');
    http.expectNone('/api/invitations/accept');
    TestBed.inject(AuthService).restore().subscribe();
    http
      .expectOne('/api/auth/refresh')
      .flush({ ...testAuthResponse(viewer), sessionId: 'replacement-device' });
    fixture.detectChanges();
    expect(root.textContent).toContain('Your session changed');
    expect(
      [...root.querySelectorAll('button')].some(
        (b) => b.textContent?.trim() === 'Preview invitation',
      ),
    ).toBe(false);
    http.expectNone('/api/invitations/preview');
    http.expectNone('/api/invitations/accept');
  });
});
