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

/** Which destructive action is showing its inline "Yes, …/Cancel" confirm. */
type Confirming = 'leave' | 'delete' | null;

/**
 * Group settings, opened from the gear in the group header. Renders inside the app shell, so
 * the sidebar stays put. Net balances and current-mode obligations together gate member
 * removal and leaving. Raw history remains unchanged when the mode changes.
 */
@Component({
  selector: 'app-group-settings',
  imports: [RouterLink, ReactiveFormsModule, GroupArtComponent],
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
  protected readonly confirming = signal<Confirming>(null);
  protected readonly busy = signal(false);

  protected groupId = '';

  protected readonly meId = computed(() => this.auth.user()?.id ?? '');
  /** Only the creator owns the group, so only they may delete it. */
  protected readonly isCreator = computed(() => this.group()?.createdBy.id === this.meId());
  protected readonly hasMyBalance = computed(() => this.hasBalance(this.meId()));
  protected readonly canLeave = computed(() => this.blockedReason(this.meId()) === null);

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
            ? 'You are not a member of this group.'
            : 'Could not load this group, its balances and obligations.',
        );
        this.loading.set(false);
      },
    });
  }

  retry(): void {
    this.reload();
  }

  balancesOf(userId: string): MemberBalance[] {
    return this.memberBalances().filter((b) => b.user.id === userId);
  }
  hasBalance(userId: string): boolean {
    return this.balancesOf(userId).some((b) => b.netMinor !== 0);
  }

  /** A member's current-mode counterparties, including people who have left the group. */
  obligationsOf(userId: string) {
    return this.obligations()
      .filter((debt) => debt.from.id === userId || debt.to.id === userId)
      .map((debt) => ({
        user: debt.from.id === userId ? debt.to : debt.from,
        netMinor: debt.from.id === userId ? -debt.amountMinor : debt.amountMinor,
        currency: debt.currency ?? 'USD',
      }));
  }

  /** Why this member can't be removed, or null when they can be. */
  blockedReason(userId: string): string | null {
    if (!this.financialsReady()) {
      return 'Balances and obligations are unavailable';
    }
    if (this.group()?.createdBy.id === userId) {
      return 'Created this group';
    }
    const balances = this.balancesOf(userId).filter((b) => b.netMinor !== 0);
    if (balances.length > 1)
      return (
        'Outstanding balances: ' +
        balances.map((b) => this.display(b.netMinor, b.currency, true)).join('; ')
      );
    if (balances.length === 1) {
      const balance = balances[0];
      return `${balance.netMinor > 0 ? 'Is owed' : 'Owes'} ${this.display(Math.abs(balance.netMinor), balance.currency)}`;
    }
    if (this.obligationsOf(userId).length) {
      return 'Has outstanding obligations';
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
      this.error.set('Group name cannot be blank.');
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
        this.error.set(err?.error?.message ?? 'Could not rename the group.');
        this.savingName.set(false);
      },
    });
  }

  // ── Members ───────────────────────────────────────────────────────────────
  addMember(): void {
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
        this.error.set(err?.error?.message ?? 'Could not remove that member.');
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
        this.error.set(err?.error?.message ?? 'Could not change that setting.');
        this.savingSimplify.set(false);
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
    this.scoped(this.groups.removeMember(this.groupId, this.meId())).subscribe({
      next: () => {
        this.dashboard.refresh();
        this.router.navigate(['/dashboard']);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? 'Could not leave the group.');
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
        this.router.navigate(['/dashboard']);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? 'Could not delete the group.');
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
    return userId === this.meId();
  }
}
