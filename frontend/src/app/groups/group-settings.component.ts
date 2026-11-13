import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subject, Subscription, forkJoin, takeUntil } from 'rxjs';
import { SessionService } from '../core/auth/session.service';
import { GroupArtComponent } from '../shared/group-art.component';
import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { avatarTint, groupGlyphTint } from '../core/avatar';
import { BalanceService, describeBalance } from '../balances/balance.service';
import { DirectObligation, MemberBalance } from '../balances/balance.models';
import { DashboardService } from '../dashboard/dashboard.service';
import { Currency } from '../shared/currency';
import { formatMoney } from '../shared/money.component';
import { ModalService } from '../modals/modal.service';
import { GroupService } from './group.service';
import { GroupResponse } from './group.models';
import { GroupType } from '../dashboard/dashboard.models';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { translate } from '../core/i18n/i18n.service';

/** Which destructive action is showing its inline "Yes, …/Cancel" confirm. */
type Confirming = 'leave' | 'delete' | null;

/**
 * Group settings, opened from the gear in the group header. Renders inside the app shell, so
 * the sidebar stays put. Net balances and current-mode obligations together gate member
 * removal and leaving. Raw history remains unchanged when the mode changes.
 */
@Component({
  selector: 'app-group-settings',
  imports: [TranslatePipe, RouterLink, ReactiveFormsModule, GroupArtComponent],
  templateUrl: './group-settings.component.html',
  styleUrl: './group-settings.component.scss',
})
export class GroupSettingsComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly session = inject(SessionService);
  private readonly scopeChanges = new Subject<void>();
  private loadRequest?: Subscription;

  /** End UI callbacks when their route, account or component no longer owns them. */
  private scoped<T>(request: Observable<T>): Observable<T> {
    return request.pipe(
      takeUntil(this.scopeChanges),
      takeUntil(this.session.changes),
      takeUntilDestroyed(this.destroyRef),
    );
  }
  private readonly router = inject(Router);
  private readonly groups = inject(GroupService);
  private readonly balances = inject(BalanceService);
  private readonly auth = inject(AuthService);
  private readonly dashboard = inject(DashboardService);
  protected readonly modal = inject(ModalService);

  protected readonly display = (amount: number, currency: Currency = 'USD', net = false) =>
    formatMoney(amount, net, currency);
  protected readonly describe = describeBalance;

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly group = signal<GroupResponse | null>(null);
  protected readonly memberBalances = signal<MemberBalance[]>([]);
  protected readonly obligations = signal<DirectObligation[]>([]);
  protected readonly financialsReady = signal(false);

  /** Inline rename: the pencil swaps the heading for this field and becomes a save button. */
  protected readonly editingName = signal(false);
  protected readonly nameControl = new FormControl('', { nonNullable: true });
  protected readonly savingName = signal(false);

  protected readonly savingSimplify = signal(false);
  protected readonly savingType = signal(false);
  protected readonly savingArchive = signal(false);

  /** Archived groups are read-only: history stays readable, new entries are refused. */
  protected readonly archived = computed(() => !!this.group()?.archivedAt);

  protected readonly groupTypes: { value: GroupType; label: string }[] = [
    {
      value: 'HOME',
      get label() {
        return translate('groupSettings.home');
      },
    },
    {
      value: 'TRIP',
      get label() {
        return translate('groupSettings.trip');
      },
    },
    {
      value: 'DINING',
      get label() {
        return translate('groupSettings.dining');
      },
    },
    {
      value: 'EVENT',
      get label() {
        return translate('groupSettings.event');
      },
    },
    {
      value: 'OTHER',
      get label() {
        return translate('groupSettings.other');
      },
    },
  ];
  protected readonly confirming = signal<Confirming>(null);
  protected readonly busy = signal(false);

  protected groupId = '';

  protected readonly viewerAccountId = computed(() => this.auth.user()?.id ?? '');
  /** Only the creator owns the group, so only they may delete it. */
  protected readonly isCreator = computed(
    () => this.group()?.createdBy.id === this.viewerAccountId(),
  );
  protected readonly hasMyBalance = computed(() => this.hasBalance(this.viewerAccountId()));
  protected readonly canLeave = computed(() => this.blockedReason(this.viewerAccountId()) === null);

  constructor() {
    this.session.changes.pipe(takeUntilDestroyed()).subscribe(() => {
      this.group.set(null);
      this.memberBalances.set([]);
      this.obligations.set([]);
      this.financialsReady.set(false);
      this.editingName.set(false);
      this.nameControl.reset('');
      this.savingName.set(false);
      this.savingSimplify.set(false);
      this.savingType.set(false);
      this.savingArchive.set(false);
      this.confirming.set(null);
      this.busy.set(false);
      this.error.set(null);
      this.loading.set(false);
    });
    this.dashboard.refreshes.pipe(takeUntilDestroyed()).subscribe((force) => {
      if (this.groupId) this.reload(force);
    });
  }

  ngOnInit(): void {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const id = params.get('id') ?? '';
      if (id === this.groupId) {
        return;
      }
      this.scopeChanges.next();
      this.group.set(null);
      this.savingName.set(false);
      this.savingSimplify.set(false);
      this.savingType.set(false);
      this.savingArchive.set(false);
      this.busy.set(false);
      this.groupId = id;
      this.loading.set(true);
      this.error.set(null);
      this.confirming.set(null);
      this.editingName.set(false);
      this.reload();
    });
  }

  private reload(force = true): void {
    if (!force && this.loadRequest && !this.loadRequest.closed) return;
    this.loadRequest?.unsubscribe();
    this.financialsReady.set(false);
    this.memberBalances.set([]);
    this.obligations.set([]);
    this.loading.set(true);
    this.error.set(null);
    this.loadRequest = this.scoped(
      forkJoin({
        group: this.groups.getGroup(this.groupId),
        balances: this.balances.getBalances(this.groupId),
        obligations: this.balances.getCurrentObligations(this.groupId),
      }),
    ).subscribe({
      next: (res) => {
        this.group.set(res.group);
        this.memberBalances.set(res.balances.balances);
        this.obligations.set(res.obligations);
        this.financialsReady.set(true);
        this.loading.set(false);
      },
      error: (err) => {
        if (err?.status === 403 || err?.status === 404) this.group.set(null);
        this.error.set(
          err?.status === 403
            ? translate('groupDetail.youAreNotA')
            : translate('groupSettings.couldNotLoadThis'),
        );
        this.loading.set(false);
      },
    });
  }

  retry(): void {
    this.reload();
  }

  /** Account membership actions inspect its canonical financial identities, never UUID coincidence. */
  private financialIdsFor(accountId: string): Set<string> {
    const group = this.group();
    if (!group?.participants) return new Set([accountId]); // Explicit pre-G01 account-only schema.
    const ids = new Set<string>();
    for (const p of [...group.participants, ...this.memberBalances().map((b) => b.user)]) {
      if (p.accountId === accountId) {
        ids.add(p.id);
        if (p.canonicalParticipantId) ids.add(p.canonicalParticipantId);
      }
    }
    if (accountId === this.viewerAccountId()) {
      for (const id of group.ownedParticipantIds ?? []) ids.add(id);
      if (group.viewerParticipantId) ids.add(group.viewerParticipantId);
    }
    return ids;
  }

  balancesOf(accountId: string): MemberBalance[] {
    const ids = this.financialIdsFor(accountId);
    return this.memberBalances().filter((b) => ids.has(b.user.id));
  }
  hasBalance(accountId: string): boolean {
    return this.balancesOf(accountId).some((b) => b.netMinor !== 0);
  }

  /** Membership is account-based; canonical obligations are participant-based. */
  obligationsOf(accountId: string) {
    const ids = this.financialIdsFor(accountId);
    return this.obligations()
      .filter((debt) => ids.has(debt.from.id) !== ids.has(debt.to.id))
      .map((debt) => ({
        user: ids.has(debt.from.id) ? debt.to : debt.from,
        netMinor: ids.has(debt.from.id) ? -debt.amountMinor : debt.amountMinor,
        currency: debt.currency ?? 'USD',
      }));
  }

  /** Why this member can't be removed, or null when they can be. */
  blockedReason(userId: string): string | null {
    if (!this.financialsReady()) {
      return translate('groupSettings.balancesAndObligationsAre2');
    }
    if (this.group()?.createdBy.id === userId) {
      return translate('groupSettings.createdThisGroup');
    }
    const balances = this.balancesOf(userId).filter((b) => b.netMinor !== 0);
    if (balances.length > 1)
      return translate('groupSettings.outstandingBalances', {
        list: balances.map((b) => this.display(b.netMinor, b.currency, true)).join('; '),
      });
    if (balances.length === 1) {
      const balance = balances[0];
      return translate(
        balance.netMinor > 0 ? 'groupSettings.isOwedAmount' : 'groupSettings.owesAmount',
        {
          amount: this.display(Math.abs(balance.netMinor), balance.currency),
        },
      );
    }
    if (this.obligationsOf(userId).length) {
      return translate('groupSettings.hasOutstandingObligations');
    }
    return null;
  }

  // ── Name ──────────────────────────────────────────────────────────────────
  startRename(): void {
    this.nameControl.setValue(this.group()?.name ?? '');
    this.editingName.set(true);
  }

  cancelRename(): void {
    this.editingName.set(false);
    this.error.set(null);
  }

  saveName(): void {
    const name = this.nameControl.value.trim();
    if (!name || this.savingName()) {
      this.error.set(translate('groupSettings.groupNameCannotBe'));
      return;
    }
    if (name === this.group()?.name) {
      this.editingName.set(false);
      return;
    }
    this.savingName.set(true);
    this.scoped(this.groups.updateGroup(this.groupId, { name })).subscribe({
      next: (g) => {
        this.group.set(g);
        this.editingName.set(false);
        this.savingName.set(false);
        this.dashboard.refresh(); // the sidebar and cards show the name too
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? translate('groupSettings.couldNotRenameThe'));
        this.savingName.set(false);
      },
    });
  }

  // ── Members ───────────────────────────────────────────────────────────────
  addMember(): void {
    if (this.archived()) return; // A new participant is a new entry.
    this.modal.openAddMember(this.groupId);
  }

  removeMember(userId: string): void {
    if (this.busy() || this.blockedReason(userId)) {
      return;
    }
    this.busy.set(true);
    this.scoped(this.groups.removeMember(this.groupId, userId)).subscribe({
      next: () => {
        this.busy.set(false);
        this.dashboard.refresh(); // also re-reads this group
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? translate('groupSettings.couldNotRemoveThat'));
        this.busy.set(false);
      },
    });
  }

  // ── Advanced ──────────────────────────────────────────────────────────────
  toggleSimplify(): void {
    const g = this.group();
    if (!g || this.savingSimplify()) {
      return;
    }
    this.savingSimplify.set(true);
    this.scoped(
      this.groups.updateGroup(this.groupId, { simplifyDebts: !g.simplifyDebts }),
    ).subscribe({
      next: (updated) => {
        this.group.set(updated);
        this.savingSimplify.set(false);
        this.dashboard.refresh(); // also rechecks group eligibility independently
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? translate('groupSettings.couldNotChangeThat'));
        this.savingSimplify.set(false);
      },
    });
  }

  /** Group type uses the same partial PATCH path as rename and simplify-debts. */
  setType(type: GroupType): void {
    const g = this.group();
    if (!g || this.savingType() || g.type === type) {
      return;
    }
    this.savingType.set(true);
    this.scoped(this.groups.updateGroup(this.groupId, { type })).subscribe({
      next: (updated) => {
        this.group.set(updated);
        this.savingType.set(false);
        this.dashboard.refresh(); // the sidebar and cards carry the glyph
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? translate('groupSettings.couldNotChangeThe'));
        this.savingType.set(false);
      },
    });
  }

  /** Archiving stops new entries; re-opening restores them. Any member, like other settings. */
  toggleArchive(): void {
    if (this.savingArchive() || !this.group()) {
      return;
    }
    const wasArchived = this.archived();
    this.savingArchive.set(true);
    this.error.set(null);
    const request = wasArchived
      ? this.groups.unarchiveGroup(this.groupId)
      : this.groups.archiveGroup(this.groupId);
    this.scoped(request).subscribe({
      next: (updated) => {
        this.group.set(updated);
        this.savingArchive.set(false);
        this.dashboard.refresh(); // cards and the tray show the archived state
      },
      error: (err) => {
        this.error.set(
          err?.error?.message ??
            (wasArchived
              ? translate('groupSettings.couldNotReOpen')
              : translate('groupSettings.couldNotArchiveThe')),
        );
        this.savingArchive.set(false);
      },
    });
  }

  // ── Danger zone ───────────────────────────────────────────────────────────
  ask(which: Confirming): void {
    this.error.set(null);
    this.confirming.set(which);
  }

  leaveGroup(): void {
    if (this.busy() || !this.canLeave()) {
      return;
    }
    this.busy.set(true);
    this.scoped(this.groups.removeMember(this.groupId, this.viewerAccountId())).subscribe({
      next: () => {
        this.dashboard.refresh();
        this.router.navigate(['/dashboard']);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? translate('groupSettings.couldNotLeaveThe'));
        this.busy.set(false);
        this.confirming.set(null);
      },
    });
  }

  deleteGroup(): void {
    if (this.busy() || !this.isCreator()) {
      return;
    }
    this.busy.set(true);
    this.scoped(this.groups.deleteGroup(this.groupId)).subscribe({
      next: () => {
        this.dashboard.refresh();
        // Land on the recovery list: deletion is reversible, and this is where to reverse it.
        this.router.navigate(['/groups/deleted']);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? translate('groupSettings.couldNotDeleteThe'));
        this.busy.set(false);
        this.confirming.set(null);
      },
    });
  }

  // ── Presentation helpers ──────────────────────────────────────────────────
  glyphTint(name: string): { background: string; color: string } {
    return groupGlyphTint(name);
  }

  tint(id: string): { background: string; color: string } {
    return avatarTint(id);
  }

  initials(name: string): string {
    return name
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]?.toUpperCase() ?? '')
      .join('');
  }

  isMe(userId: string): boolean {
    return userId === this.viewerAccountId();
  }
}
