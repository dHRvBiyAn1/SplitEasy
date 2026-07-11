import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';

const USER_KEY = 'spliteasy.user';
const TOKEN_KEY = 'spliteasy.accessToken';

function signIn(): void {
  localStorage.setItem(TOKEN_KEY, 'test-token');
  localStorage.setItem(USER_KEY, JSON.stringify({ id: 'u1', email: 'a@b.com', displayName: 'Alex Rivera' }));
}

describe('App', () => {
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
    fixture.detectChanges();
    // Unauthenticated: no shell, no dashboard fetch.
    httpTesting.expectNone('/api/dashboard');
  });

  it('hides the sidebar shell when unauthenticated', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.side')).toBeNull();
  });

  it('shows the sidebar and loads the dashboard when authenticated', () => {
    signIn();
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    // The shell effect pulls the dashboard payload for the sidebar group balances.
    const req = httpTesting.expectOne('/api/dashboard');
    req.flush({
      totalNetCents: 0,
      owedCents: 0,
      owedPeopleCount: 0,
      oweCents: 0,
      owePeopleCount: 0,
      groupCount: 0,
      groups: [],
      people: [],
      settlements: [],
      activity: [],
    });
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.side__brand')?.textContent).toContain('Evenly');
    expect(compiled.querySelector('.side__user-name')?.textContent).toContain('Alex Rivera');
  });
});
