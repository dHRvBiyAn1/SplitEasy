import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';

describe('App', () => {
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
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
    httpTesting.expectOne('/api/health');
  });

  it('renders the brand title and checks backend health', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const req = httpTesting.expectOne('/api/health');
    req.flush({ status: 'UP', service: 'spliteasy-backend', timestamp: '2026-01-01T00:00:00Z' });
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.brand')?.textContent).toContain('SplitEasy');
    expect(compiled.querySelector('.backend-status')?.textContent?.toLowerCase()).toContain('up');
  });

  it('shows login/register links when unauthenticated', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    httpTesting.expectOne('/api/health').flush({ status: 'UP', service: 's', timestamp: 't' });
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Log in');
    expect(text).toContain('Register');
  });
});
