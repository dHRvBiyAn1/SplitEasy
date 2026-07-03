import { Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatToolbarModule } from '@angular/material/toolbar';
import { AuthService } from './core/auth/auth.service';
import { HealthService } from './core/api/health.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, MatToolbarModule, MatButtonModule],
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
