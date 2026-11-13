import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DestroyRef, Component, computed, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { concatMap, from, of, takeUntil } from 'rxjs';
import { catchError, map, toArray } from 'rxjs/operators';
import { DashboardService } from '../dashboard/dashboard.service';
import { PendingWritesService, uncertainWrite } from '../core/pending-writes.service';
import { SessionService } from '../core/auth/session.service';
import { GroupType } from '../dashboard/dashboard.models';
import { GroupService } from '../groups/group.service';
import { ModalService } from './modal.service';
import { ModalShellComponent } from './modal-shell.component';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { translate } from '../core/i18n/i18n.service';

const TYPES: { value: GroupType; label: string }[] = [
  { value: 'HOME', label: translate('groupSettings.home') },
  { value: 'TRIP', label: translate('groupSettings.trip') },
  { value: 'DINING', label: translate('groupSettings.dining') },
  { value: 'EVENT', label: translate('groupSettings.event') },
  { value: 'OTHER', label: translate('groupSettings.other') },
];

@Component({
  selector: 'app-new-group-modal',
  imports: [TranslatePipe, ReactiveFormsModule, ModalShellComponent],
  templateUrl: './new-group-modal.component.html',
  styleUrl: './modals.scss',
})
export class NewGroupModalComponent {
  private readonly pendingWrites = inject(PendingWritesService);
  protected readonly retryNotice = signal<string | null>(null);
  private readonly groups = inject(GroupService);
  private readonly dashboard = inject(DashboardService);
  private readonly modal = inject(ModalService);
  private readonly router = inject(Router);
  private readonly session = inject(SessionService);
  private readonly sessionVersion = this.session.version;
  private readonly destroyRef = inject(DestroyRef);

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

  constructor() {
    try {
      const pending = this.pendingWrites.read<{ name: string; type: GroupType }>('group');
      if (pending) {
        this.name.setValue(pending.request.name);
        this.type.set(pending.request.type);
        this.emails.set((pending.draft as { emails?: string[] })?.emails ?? []);
        this.retryNotice.set(uncertainWrite());
      }
    } catch {
      this.error.set(translate('pendingWrites.cannotReadTheSaved'));
    }

    this.destroyRef.onDestroy(() => {
      if (this.session.version === this.sessionVersion) this.modal.setMutationPending(false);
    });
  }

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
    if (this.session.version !== this.sessionVersion) return;
    this.modal.requestClose();
  }

  viewGroup(): void {
    if (this.session.version !== this.sessionVersion) return;
    const id = this.createdGroupId();
    if (id && !this.saving()) {
      this.modal.close();
      this.router.navigate(['/groups', id]);
    }
  }

  save(): void {
    if (this.session.version !== this.sessionVersion) return;
    if (this.name.invalid || this.saving()) {
      return;
    }
    const groupId = this.createdGroupId();
    if (groupId) {
      this.addMembers(groupId, this.failedEmails());
      return;
    }
    let pending;
    try {
      pending = this.pendingWrites.prepare(
        'group',
        'new',
        { name: this.name.value.trim(), type: this.type() },
        { emails: this.emails() },
      );
    } catch (error) {
      this.error.set((error as Error).message);
      this.retryNotice.set(uncertainWrite());
      return;
    }
    this.saving.set(true);
    this.modal.setMutationPending(true);
    this.error.set(null);
    const emails = [...this.emails()];
    this.groups
      .createGroup(pending.request.name, pending.request.type, pending.operationId)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (group) => {
          this.pendingWrites.complete('group', pending);
          this.retryNotice.set(null);
          this.createdGroupId.set(group.id);
          this.addMembers(group.id, emails);
        },
        error: (err) => {
          this.error.set(err?.error?.message ?? translate('newGroupModal.couldNotCreateThe'));
          this.retryNotice.set(uncertainWrite());
          this.saving.set(false);
          this.modal.setMutationPending(false);
        },
      });
  }

  retryOriginal(): void {
    if (this.saving() || this.session.version !== this.sessionVersion) return;
    try {
      const pending = this.pendingWrites.read<{ name: string; type: GroupType }>('group');
      if (!pending) return;
      this.name.setValue(pending.request.name);
      this.type.set(pending.request.type);
      this.emails.set((pending.draft as { emails?: string[] })?.emails ?? []);
      this.save();
    } catch {
      this.error.set(translate('newGroupModal.cannotReadTheSaved'));
    }
  }

  abandonRetry(): void {
    if (this.saving() || this.session.version !== this.sessionVersion) return;
    if (!confirm(translate('newGroupModal.thePreviousGroupMay'))) return;
    try {
      this.pendingWrites.abandon('group');
      this.retryNotice.set(null);
      this.error.set(null);
    } catch {
      this.error.set(translate('personalExpenses.cannotClearTheSaved'));
    }
  }

  private addMembers(groupId: string, emails: string[]): void {
    this.saving.set(true);
    this.modal.setMutationPending(true);
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
                    ? translate('newGroupModal.noAccountFoundWith')
                    : err.status === 409
                      ? translate('newGroupModal.alreadyAMemberOf')
                      : translate('newGroupModal.couldNotAddThis'),
              }),
            ),
          ),
        ),
        toArray(),
        takeUntil(this.session.changes),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((results) => {
        this.results.update((previous) => [
          ...previous.filter((result) => !emails.includes(result.email)),
          ...results,
        ]);
        this.saving.set(false);
        this.modal.setMutationPending(false);
        this.dashboard.refresh();
        if (!this.results().length) this.viewGroup();
      });
  }
}
