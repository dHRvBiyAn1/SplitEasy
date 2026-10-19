import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { ProfileComponent } from './profile.component';

describe('truthful profile preferences', () => {
  it('keeps server identity read-only while editing device preferences', () => {
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
    const edit = fixture.nativeElement.querySelector('.pcard__edit') as HTMLButtonElement;
    expect(edit.textContent).toContain('Edit device preferences');
    edit.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('input[autocomplete=name]')).toBeNull();
    expect(fixture.nativeElement.querySelector('input[type=email]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('alice@example.com');
    expect(fixture.nativeElement.textContent).toContain(
      'Phone, avatar and currency preferences are saved on this device. Currency preferences do not change or convert recorded amounts.',
    );
    http.verify();
  });
});
