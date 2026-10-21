import {
  Component,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { filter } from 'rxjs';
import { AuthService } from './core/auth/auth.service';
import { SessionService } from './core/auth/session.service';
import { DashboardService } from './dashboard/dashboard.service';
import { ProfileService } from './core/profile.service';
import { ThemeService } from './core/theme.service';
import { RibbonService } from './core/ribbon.service';
import { ModalsComponent } from './modals/modals.component';
import { ModalService } from './modals/modal.service';
import { ModalShellComponent } from './modals/modal-shell.component';
import { IconComponent } from './shared/icon.component';
import { GroupArtComponent } from './shared/group-art.component';
import { MoneyComponent } from './shared/money.component';

@Component({
  selector: 'app-root',
  imports: [
    RouterOutlet,
    RouterLink,
    ModalsComponent,
    ModalShellComponent,
    IconComponent,
    GroupArtComponent,
    MoneyComponent,
  ],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly auth = inject(AuthService);
  private readonly dashboard = inject(DashboardService);
  private readonly profile = inject(ProfileService);
  private readonly theme = inject(ThemeService);
  private readonly ribbon = inject(RibbonService);
  protected readonly modal = inject(ModalService);
  private readonly router = inject(Router);
  protected readonly user = this.auth.user;
  protected readonly groups = computed(() => this.dashboard.data()?.groups ?? []);
  protected readonly staleOverview = computed(
    () => this.dashboard.error() && this.dashboard.data() !== null,
  );
  protected readonly groupsOpen = signal(false);
  private readonly currentUrl = signal(this.router.url);
  protected readonly isAuthRoute = computed(() =>
    /^\/(login|register|forgot-password|account-action)(?:[/?#]|$)/.test(this.currentUrl()),
  );
  protected readonly showShell = computed(() => this.auth.isAuthenticated() && !this.isAuthRoute());
  protected readonly blocked = computed(() => !!this.modal.active() || this.groupsOpen());
  protected readonly activeNav = computed(() => {
    if (this.modal.active() === 'expense') return 'expense';
    if (this.modal.active() === 'appearance') return 'profile';
    if (
      this.groupsOpen() ||
      this.currentUrl().startsWith('/groups') ||
      this.modal.active() === 'group' ||
      this.modal.active() === 'member'
    )
      return 'groups';
    return this.currentUrl().startsWith('/profile') ? 'profile' : 'overview';
  });
  protected readonly avatarStyle = computed(() => ({
    background: `color-mix(in oklab, ${this.profile.prefs().avatarColor} 24%, var(--surface))`,
    color: `color-mix(in oklab, ${this.profile.prefs().avatarColor}, var(--avatar-ink) 30%)`,
  }));

  constructor() {
    this.router.events
      .pipe(
        filter((e): e is NavigationEnd => e instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe((e) => {
        this.currentUrl.set(e.urlAfterRedirects);
        this.groupsOpen.set(false);
      });
    inject(SessionService)
      .changes.pipe(takeUntilDestroyed())
      .subscribe(() => this.groupsOpen.set(false));
    effect(() => {
      if (this.showShell()) untracked(() => this.dashboard.ensureLoaded());
    });
    effect(() => {
      if (this.modal.active()) this.groupsOpen.set(false);
    });
    afterNextRender(() => this.ribbon.init());
  }
  skipToMain(event: Event): void {
    event.preventDefault();
    const main = document.getElementById('main-content');
    main?.focus();
    main?.scrollIntoView({ block: 'start' });
  }
  retryOverview(): void {
    this.dashboard.refresh();
  }
  openExpense(): void {
    this.groupsOpen.set(false);
    const groupId = this.currentUrl().match(/^\/groups\/([^/?#]+)/)?.[1];
    this.modal.openExpense(groupId);
  }
  initials(name: string | undefined): string {
    return (name ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]?.toUpperCase() ?? '')
      .join('');
  }
}
