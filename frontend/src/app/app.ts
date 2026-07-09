import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from './core/auth/auth.service';
import { HealthService } from './core/api/health.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit {
  protected readonly title = signal('SplitEasy');
  protected readonly backendStatus = signal<'checking' | 'up' | 'down'>('checking');

  private readonly auth = inject(AuthService);
  private readonly healthService = inject(HealthService);
  private readonly router = inject(Router);

  protected readonly user = this.auth.user;
  protected readonly isAuthenticated = this.auth.isAuthenticated;

  /** Current URL, kept live so the toolbar can hide itself on the full-bleed auth screens. */
  private readonly currentUrl = signal(this.router.url);
  protected readonly isAuthRoute = computed(() => {
    const url = this.currentUrl();
    return url.startsWith('/login') || url.startsWith('/register');
  });

  constructor() {
    this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe((e) => this.currentUrl.set(e.urlAfterRedirects));
  }

  ngOnInit(): void {
    this.healthService.check().subscribe({
      next: (health) => this.backendStatus.set(health.status === 'UP' ? 'up' : 'down'),
      error: () => this.backendStatus.set('down'),
    });
  }

  logout(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
