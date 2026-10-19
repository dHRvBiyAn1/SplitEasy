import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DestroyRef, Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { takeUntil } from 'rxjs';
import { avatarTint } from '../core/avatar';
import { UserSummary } from '../core/auth/auth.models';
import { SessionService } from '../core/auth/session.service';
import { DashboardService } from '../dashboard/dashboard.service';
import { GroupService } from '../groups/group.service';
import { ModalService } from './modal.service';
import { ModalShellComponent } from './modal-shell.component';

/**
 * Add an existing user (by email) to a group. Any member can add — the backend only enforces
 * membership. ponytail: adds existing users by email (like the new-group modal); the mock's
 * "add someone brand-new by name" would need placeholder users the backend doesn't model.
 */
@Component({
  selector: 'app-add-member-modal',
  imports: [ReactiveFormsModule, ModalShellComponent],
  templateUrl: './add-member-modal.component.html',
  styleUrl: './modals.scss',
})
export class AddMemberModalComponent {
  private readonly groups = inject(GroupService);
  private readonly dashboard = inject(DashboardService);
  private readonly modal = inject(ModalService);
  private readonly session = inject(SessionService);
  private readonly sessionVersion = this.session.version;
  private readonly destroyRef = inject(DestroyRef);

  protected readonly groupId = this.modal.addMemberGroupId();
  protected readonly groupName = signal('');
  protected readonly members = signal<UserSummary[]>([]);
  protected readonly email = new FormControl('', { nonNullable: true });
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly membersLoading = signal(false);
  protected readonly membersFailed = signal(false);

  constructor() {
    this.destroyRef.onDestroy(() => {
      if (this.session.version === this.sessionVersion) this.modal.setMutationPending(false);
    });
    this.retryMembers();
  }

  retryMembers(): void {
    if (this.session.version !== this.sessionVersion) return;
    if (!this.groupId || this.membersLoading() || this.saving()) return;
    this.membersLoading.set(true);
    this.membersFailed.set(false);
    this.error.set(null);
    this.groups
      .getGroup(this.groupId)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (g) => {
          this.groupName.set(g.name);
          this.members.set(g.members);
          this.membersLoading.set(false);
        },
        error: () => {
          this.membersLoading.set(false);
          this.membersFailed.set(true);
          this.error.set('Could not load group members.');
        },
      });
  }

  initials(name: string): string {
    return name
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]?.toUpperCase() ?? '')
      .join('');
  }

  tint(id: string): { background: string; color: string } {
    return avatarTint(id);
  }

  close(): void {
    if (this.session.version !== this.sessionVersion) return;
    this.modal.requestClose();
  }

  add(): void {
    if (this.session.version !== this.sessionVersion) return;
    const email = this.email.value.trim();
    if (!email || !this.groupId || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.modal.setMutationPending(true);
    this.error.set(null);
    this.groups
      .addMember(this.groupId, email)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (g) => {
          this.members.set(g.members);
          this.email.reset();
          this.saving.set(false);
          this.modal.setMutationPending(false);
          this.dashboard.refresh();
        },
        error: (err) => {
          this.error.set(err?.error?.message ?? 'Could not add that member.');
          this.saving.set(false);
          this.modal.setMutationPending(false);
        },
      });
  }
}
