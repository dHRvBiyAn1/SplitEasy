import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { App } from './app';

describe('App', () => {
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
    fixture.detectChanges();
    httpTesting.expectOne('/api/health');
  });

  it('should render title', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    httpTesting.expectOne('/api/health');
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('h1')?.textContent).toContain('SplitEasy');
  });

  it('should show backend UP when health check succeeds', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const req = httpTesting.expectOne('/api/health');
    req.flush({ status: 'UP', service: 'spliteasy-backend', timestamp: '2026-01-01T00:00:00Z' });
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.backend-status')?.textContent).toContain('UP');
  });

  it('should show backend unreachable when health check fails', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const req = httpTesting.expectOne('/api/health');
    req.error(new ProgressEvent('error'));
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.backend-status')?.textContent).toContain('unreachable');
  });
});
