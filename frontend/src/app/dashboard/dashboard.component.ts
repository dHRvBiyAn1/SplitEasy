import { GroupArtComponent } from '../shared/group-art.component';
import { MoneyComponent, formatMoney } from '../shared/money.component';
import { IconComponent } from '../shared/icon.component';
import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { ParticipantSummary } from '../groups/participant.models';
import { avatarTint } from '../core/avatar';
import { ModalService } from '../modals/modal.service';
import { ActivityItem, ExpenseCategory, dashboardTotals, groupTotals } from './dashboard.models';
import { DashboardService } from './dashboard.service';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { translate } from '../core/i18n/i18n.service';

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
  imports: [TranslatePipe, RouterLink, GroupArtComponent, MoneyComponent, IconComponent],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent {
  private readonly dashboard = inject(DashboardService);
  private readonly auth = inject(AuthService);
  protected readonly modal = inject(ModalService);

  protected readonly viewerInitials = computed(() =>
    this.initials(this.auth.user()?.displayName ?? ''),
  );
  protected readonly data = this.dashboard.data;
  protected readonly loading = this.dashboard.loading;
  protected readonly totals = dashboardTotals;
  protected readonly groupTotals = groupTotals;

  protected readonly firstNameOf = computed(() =>
    this.firstName(this.auth.user()?.displayName ?? ''),
  );

  constructor() {
    this.dashboard.ensureLoaded();
  }

  retry(): void {
    this.dashboard.revalidate();
  }

  greeting(): string {
    const h = new Date().getHours();
    if (h < 12) return translate('dashboard.goodMorning');
    if (h < 18) return translate('dashboard.goodAfternoon');
    return translate('dashboard.goodEvening');
  }

  firstName(name: string): string {
    return (name ?? '').split(/\s+/).filter(Boolean)[0] ?? '';
  }

  isMe(user: ParticipantSummary | null): boolean {
    const data = this.data();
    // Absence declares the legacy account-only schema, never a UUID-domain guess.
    const owned =
      data?.ownedParticipantIds ??
      (data?.viewerParticipantId ? [data.viewerParticipantId] : [this.auth.user()?.id ?? '']);
    return !!user && owned.includes(user.id);
  }

  initials(name: string): string {
    return (name ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]?.toUpperCase() ?? '')
      .join('');
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
      return translate('dashboard.groupnameSettlement', { groupName: a.groupName });
    }
    const who = this.isMe(a.actor)
      ? translate('dashboard.you')
      : this.firstName(a.actor.displayName);
    return translate('dashboard.whoPaidAmountGroup', {
      who,
      amount: formatMoney(a.amountMinor, false, a.currency ?? 'USD'),
      groupName: a.groupName,
    });
  }

  activityTitle(a: ActivityItem): string {
    if (a.kind === 'PAYMENT') {
      const payer = this.isMe(a.actor)
        ? translate('dashboard.you')
        : this.firstName(a.actor.displayName);
      const payee = this.isMe(a.counterparty)
        ? 'you'
        : this.firstName(a.counterparty?.displayName ?? '');
      return translate('dashboard.payerPaidPayee', { payer, payee });
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
