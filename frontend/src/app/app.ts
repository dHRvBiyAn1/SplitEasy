import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { HealthService } from './core/api/health.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App implements OnInit {
  protected readonly title = signal('SplitEasy');
  protected readonly backendStatus = signal<'checking' | 'up' | 'down'>('checking');

  private readonly healthService = inject(HealthService);

  ngOnInit(): void {
    this.healthService.check().subscribe({
      next: (health) => this.backendStatus.set(health.status === 'UP' ? 'up' : 'down'),
      error: () => this.backendStatus.set('down'),
    });
  }
}
