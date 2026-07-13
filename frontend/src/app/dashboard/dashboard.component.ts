import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { UserSummary } from '../core/auth/auth.models';
import { centsToDisplay } from '../expenses/expense.service';
import { avatarTint } from '../core/avatar';
import { ModalService } from '../modals/modal.service';
import { ActivityItem, ExpenseCategory } from './dashboard.models';
import { DashboardService } from './dashboard.service';

/** Category / payment → a glyph for the activity + expense rows. */
const CATEGORY_GLYPH: Record<ExpenseCategory, string> = {
  FOOD_DRINK: '☕',
  GROCERIES: '🧺',
  RENT_HOME: '⌂',
  UTILITIES: '⚡',
  TRAVEL: '✈',
  TRANSPORT: '🚕',
  FUN: '✦',
  OTHER: '◆',
};

@Component({
  selector: 'app-dashboard',
  imports: [RouterLink],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent {
  private readonly dashboard = inject(DashboardService);
  private readonly auth = inject(AuthService);
  protected readonly modal = inject(ModalService);

  protected readonly data = this.dashboard.data;
  protected readonly loading = this.dashboard.loading;
  protected readonly display = centsToDisplay;

  protected readonly firstNameOf = computed(() => this.firstName(this.auth.user()?.displayName ?? ''));

  constructor() {
    this.dashboard.ensureLoaded();
  }

  /** "$1,136.82" — grouped thousands, always two decimals, from integer cents. */
  money(cents: number): string {
    return (Math.abs(cents) / 100).toLocaleString('en-US', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
  }

  greeting(): string {
    const h = new Date().getHours();
    if (h < 12) return 'Good morning';
    if (h < 18) return 'Good afternoon';
    return 'Good evening';
  }

  todayLabel(): string {
    return new Date()
      .toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric' })
      .toUpperCase();
  }

  firstName(name: string): string {
    return (name ?? '').split(/\s+/).filter(Boolean)[0] ?? '';
  }

  isMe(user: UserSummary | null): boolean {
    return !!user && user.id === this.auth.user()?.id;
  }

  initials(name: string): string {
    return (name ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]?.toUpperCase() ?? '')
      .join('');
  }

  glyphHue(name: string): number {
    let h = 0;
    for (let i = 0; i < name.length; i++) {
      h = (h * 31 + name.charCodeAt(i)) % 360;
    }
    return h;
  }

  tint(id: string): { background: string; color: string } {
    return avatarTint(id);
  }

  activityGlyph(a: ActivityItem): string {
    if (a.kind === 'PAYMENT') return '⇄';
    return CATEGORY_GLYPH[a.category ?? 'OTHER'];
  }

  /** Sub-line for an activity row: who paid what, in which group. */
  activitySub(a: ActivityItem): string {
    if (a.kind === 'PAYMENT') {
      return `${a.groupName} · settlement`;
    }
    const who = this.isMe(a.actor) ? 'You' : this.firstName(a.actor.displayName);
    return `${who} paid $${this.display(a.amountCents)} · ${a.groupName}`;
  }

  activityTitle(a: ActivityItem): string {
    if (a.kind === 'PAYMENT') {
      const payer = this.isMe(a.actor) ? 'You' : this.firstName(a.actor.displayName);
      const payee = this.isMe(a.counterparty) ? 'you' : this.firstName(a.counterparty?.displayName ?? '');
      return `${payer} paid ${payee}`;
    }
    return a.description;
  }

  /** "JUL 2" style short date from an ISO date string. */
  shortDate(iso: string): string {
    return new Date(iso + 'T00:00:00')
      .toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
      .toUpperCase();
  }
}
