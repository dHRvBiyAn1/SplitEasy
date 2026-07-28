import { Component, OnInit, computed, effect, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { avatarTint, groupGlyphTint } from '../core/avatar';
import { BalanceService, describeBalance } from '../balances/balance.service';
import { MemberBalance } from '../balances/balance.models';
import { DashboardService } from '../dashboard/dashboard.service';
import { centsToDisplay } from '../expenses/expense.service';
import { ModalService } from '../modals/modal.service';
import { GroupService } from './group.service';
import { GroupResponse } from './group.models';

/** Which destructive action is showing its inline "Yes, …/Cancel" confirm. */
type Confirming = 'leave' | 'delete' | null;

/**
 * Group settings, opened from the gear in the group header. Renders inside the app shell, so
 * the sidebar stays put. Member balances come from the shared /balances endpoint — the same
 * numbers the group page shows — and gate who can be removed and whether you can leave.
 */
@Component({
  selector: 'app-group-settings',
  imports: [RouterLink, ReactiveFormsModule],
  templateUrl: './group-settings.component.html',
  styleUrl: './group-settings.component.scss',
})
export class GroupSettingsComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly groups = inject(GroupService);
  private readonly balances = inject(BalanceService);
  private readonly auth = inject(AuthService);
  private readonly dashboard = inject(DashboardService);
  protected readonly modal = inject(ModalService);

  protected readonly display = centsToDisplay;
  protected readonly describe = describeBalance;

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly group = signal<GroupResponse | null>(null);
  protected readonly memberBalances = signal<MemberBalance[]>([]);

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
  protected readonly myNet = computed(() => this.netOf(this.meId()));
  protected readonly canLeave = computed(() => !this.isCreator() && this.myNet() === 0);

  constructor() {
    // A modal action (add member) refreshes the dashboard; re-read so the list stays in step.
    effect(() => {
      this.dashboard.data();
      if (this.groupId) {
        this.reload();
      }
    });
  }

  ngOnInit(): void {
    this.route.paramMap.subscribe((params) => {
      const id = params.get('id') ?? '';
      if (id === this.groupId) {
        return;
      }
      this.groupId = id;
      this.loading.set(true);
      this.error.set(null);
      this.confirming.set(null);
      this.editingName.set(false);
      this.reload();
    });
  }

  private reload(): void {
    this.groups.getGroup(this.groupId).subscribe({
      next: (g) => {
        this.group.set(g);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(
          err?.status === 403 ? 'You are not a member of this group.' : 'Could not load the group.',
        );
        this.loading.set(false);
      },
    });
    this.balances.getBalances(this.groupId).subscribe({
      next: (res) => this.memberBalances.set(res.balances),
    });
  }

  /** That member's net in this group; 0 when they have no activity yet. */
  netOf(userId: string): number {
    return this.memberBalances().find((b) => b.user.id === userId)?.netCents ?? 0;
  }

  /** Why this member can't be removed, or null when they can be. */
  blockedReason(userId: string): string | null {
    if (this.group()?.createdBy.id === userId) {
      return 'Created this group';
    }
    const net = this.netOf(userId);
    if (net > 0) {
      return `Is owed $${this.display(net)}`;
    }
    if (net < 0) {
      return `Owes $${this.display(-net)}`;
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
    this.groups.updateGroup(this.groupId, { name }).subscribe({
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
    this.groups.removeMember(this.groupId, userId).subscribe({
      next: () => {
        this.busy.set(false);
        this.dashboard.refresh(); // effect() re-reads this group
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
    this.groups.updateGroup(this.groupId, { simplifyDebts: !g.simplifyDebts }).subscribe({
      next: (updated) => {
        this.group.set(updated);
        this.savingSimplify.set(false);
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
    this.groups.removeMember(this.groupId, this.meId()).subscribe({
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
    this.groups.deleteGroup(this.groupId).subscribe({
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
