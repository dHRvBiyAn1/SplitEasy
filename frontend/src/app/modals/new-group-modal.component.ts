import { Component, computed, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { concatMap, from, of, takeUntil } from 'rxjs';
import { catchError, map, toArray } from 'rxjs/operators';
import { DashboardService } from '../dashboard/dashboard.service';
import { SessionService } from '../core/auth/session.service';
import { GroupType } from '../dashboard/dashboard.models';
import { GroupService } from '../groups/group.service';
import { ModalService } from './modal.service';
import { ModalShellComponent } from './modal-shell.component';

const TYPES: { value: GroupType; label: string }[] = [
  { value: 'HOME', label: 'Home' },
  { value: 'TRIP', label: 'Trip' },
  { value: 'DINING', label: 'Dining' },
  { value: 'EVENT', label: 'Event' },
  { value: 'OTHER', label: 'Other' },
];

@Component({
  selector: 'app-new-group-modal',
  imports: [ReactiveFormsModule, ModalShellComponent],
  templateUrl: './new-group-modal.component.html',
  styleUrl: './modals.scss',
})
export class NewGroupModalComponent {
  private readonly groups = inject(GroupService);
  private readonly dashboard = inject(DashboardService);
  private readonly modal = inject(ModalService);
  private readonly router = inject(Router);
  private readonly session = inject(SessionService);

  protected readonly types = TYPES;
  protected readonly name = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required],
  });
  protected readonly type = signal<GroupType>('HOME');
  protected readonly emails = signal<string[]>([]);
  protected readonly emailInput = new FormControl('', { nonNullable: true });
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly createdGroupId = signal<string | null>(null);
  protected readonly results = signal<{ email: string; reason: string | null }[]>([]);
  protected readonly failedEmails = computed(() =>
    this.results()
      .filter((result) => result.reason !== null)
      .map((result) => result.email),
  );

  addEmail(): void {
    const e = this.emailInput.value.trim().toLowerCase();
    if (e && !this.emails().includes(e)) {
      this.emails.update((l) => [...l, e]);
    }
    this.emailInput.reset();
  }

  removeEmail(e: string): void {
    this.emails.update((l) => l.filter((x) => x !== e));
  }

  close(): void {
    this.modal.close();
  }

  viewGroup(): void {
    const id = this.createdGroupId();
    if (id && !this.saving()) {
      this.modal.close();
      this.router.navigate(['/groups', id]);
    }
  }

  save(): void {
    if (this.name.invalid || this.saving()) {
      return;
    }
    const groupId = this.createdGroupId();
    if (groupId) {
      this.addMembers(groupId, this.failedEmails());
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    const emails = [...this.emails()];
    this.groups
      .createGroup(this.name.value.trim(), this.type())
      .pipe(takeUntil(this.session.changes))
      .subscribe({
        next: (group) => {
          this.createdGroupId.set(group.id);
          this.addMembers(group.id, emails);
        },
        error: (err) => {
          this.error.set(err?.error?.message ?? 'Could not create the group.');
          this.saving.set(false);
        },
      });
  }

  private addMembers(groupId: string, emails: string[]): void {
    this.saving.set(true);
    from(emails)
      .pipe(
        concatMap((email) =>
          this.groups.addMember(groupId, email).pipe(
            map(() => ({ email, reason: null as string | null })),
            catchError((err) =>
              of({
                email,
                reason:
                  err.status === 404
                    ? 'No account found with this email.'
                    : err.status === 409
                      ? 'Already a member of this group.'
                      : 'Could not add this member. Please try again.',
              }),
            ),
          ),
        ),
        toArray(),
        takeUntil(this.session.changes),
      )
      .subscribe((results) => {
        this.results.update((previous) => [
          ...previous.filter((result) => !emails.includes(result.email)),
          ...results,
        ]);
        this.saving.set(false);
        this.dashboard.refresh();
        if (!this.results().length) this.viewGroup();
      });
  }
}
