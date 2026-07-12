import { Component, OnInit, computed, effect, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from './core/auth/auth.service';
import { DashboardService } from './dashboard/dashboard.service';
import { centsToDisplay } from './expenses/expense.service';
import { ModalsComponent } from './modals/modals.component';
import { ModalService } from './modals/modal.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ModalsComponent],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit {
  protected readonly title = signal('Evenly');

  private readonly auth = inject(AuthService);
  private readonly dashboard = inject(DashboardService);
  protected readonly modal = inject(ModalService);
  private readonly router = inject(Router);

  protected readonly user = this.auth.user;
  protected readonly isAuthenticated = this.auth.isAuthenticated;
  protected readonly display = centsToDisplay;

  /** Group list + balances for the sidebar come from the shared dashboard payload. */
  protected readonly groups = computed(() => this.dashboard.data()?.groups ?? []);

  private readonly currentUrl = signal(this.router.url);
  protected readonly isAuthRoute = computed(() => {
    const url = this.currentUrl();
    return url.startsWith('/login') || url.startsWith('/register');
  });
  /** The sidebar shows only for a signed-in user off the full-bleed auth screens. */
  protected readonly showShell = computed(() => this.isAuthenticated() && !this.isAuthRoute());

  /** Reflects the OS theme in the sidebar indicator (the theme itself is CSS-driven). */
  protected readonly systemDark = signal(this.prefersDark());

  constructor() {
    this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe((e) => this.currentUrl.set(e.urlAfterRedirects));

    const mql = window.matchMedia?.('(prefers-color-scheme: dark)');
    mql?.addEventListener?.('change', (e) => this.systemDark.set(e.matches));

    // Pull the dashboard payload whenever the shell is visible and we don't have it yet.
    effect(() => {
      if (this.showShell()) {
        this.dashboard.ensureLoaded();
      }
    });
  }

  ngOnInit(): void {
    // no-op: data loading is driven by the effect above.
  }

  private prefersDark(): boolean {
    return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
  }

  logout(): void {
    this.auth.logout();
    this.dashboard.clear();
    this.router.navigate(['/login']);
  }

  /** Up to two initials, e.g. "Alex Rivera" → "AR". */
  initials(name: string | undefined): string {
    return (name ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]?.toUpperCase() ?? '')
      .join('');
  }

  /** Stable per-group tint for the glyph square, derived from the name. */
  glyphHue(name: string): number {
    let h = 0;
    for (let i = 0; i < name.length; i++) {
      h = (h * 31 + name.charCodeAt(i)) % 360;
    }
    return h;
  }
}
