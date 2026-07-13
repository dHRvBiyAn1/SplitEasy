import { Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { avatarTint } from '../core/avatar';
import { UserSummary } from '../core/auth/auth.models';
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

  protected readonly groupId = this.modal.addMemberGroupId();
  protected readonly groupName = signal('');
  protected readonly members = signal<UserSummary[]>([]);
  protected readonly email = new FormControl('', { nonNullable: true });
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    if (this.groupId) {
      this.groups.getGroup(this.groupId).subscribe((g) => {
        this.groupName.set(g.name);
        this.members.set(g.members);
      });
    }
  }

  initials(name: string): string {
    return name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]?.toUpperCase() ?? '').join('');
  }

  tint(id: string): { background: string; color: string } {
    return avatarTint(id);
  }

  close(): void {
    this.modal.close();
  }

  add(): void {
    const email = this.email.value.trim();
    if (!email || !this.groupId || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.groups.addMember(this.groupId, email).subscribe({
      next: (g) => {
        this.members.set(g.members);
        this.email.reset();
        this.saving.set(false);
        this.dashboard.refresh();
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? 'Could not add that member.');
        this.saving.set(false);
      },
    });
  }
}
