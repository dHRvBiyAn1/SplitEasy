import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DestroyRef, Component, computed, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { takeUntil } from 'rxjs';
import { avatarTint } from '../core/avatar';
import { InvitationService } from '../invitations/invitation.service';
import {
  InvitationStatus,
  EmailInvitationRequest,
  invitationDate,
} from '../invitations/invitation.models';
import { financialRoster } from '../groups/group.models';
import { ParticipantSummary } from '../groups/participant.models';
import { UserSummary } from '../core/auth/auth.models';
import { SessionService } from '../core/auth/session.service';
import { DashboardService } from '../dashboard/dashboard.service';
import { GroupService } from '../groups/group.service';
import { ModalService } from './modal.service';
import { ModalShellComponent } from './modal-shell.component';

/** Account access and financial guests remain separate, explicit actions. */
@Component({
  selector: 'app-add-member-modal',
  imports: [ReactiveFormsModule, ModalShellComponent],
  templateUrl: './add-member-modal.component.html',
  styleUrl: './modals.scss',
})
export class AddMemberModalComponent {
  private readonly invitations = inject(InvitationService);
  private readonly groups = inject(GroupService);
  private readonly dashboard = inject(DashboardService);
  private readonly modal = inject(ModalService);
  private readonly session = inject(SessionService);
  private readonly sessionVersion = this.session.version;
  private readonly destroyRef = inject(DestroyRef);

  protected readonly localDate = invitationDate;
  invitationKind(kind: string): string {
    return kind === 'EMAIL'
      ? 'Email invitation'
      : kind === 'LINK'
        ? 'Group join link'
        : 'Invitation';
  }
  invitationStatus(status: string): string {
    return (
      (
        {
          PENDING: 'Pending',
          ACCEPTED: 'Accepted',
          REVOKED: 'Revoked',
          EXPIRED: 'Expired',
        } as Record<string, string>
      )[status] ?? 'Unavailable'
    );
  }
  protected readonly groupId = this.modal.addMemberGroupId();
  protected readonly groupName = signal('');
  protected readonly members = signal<UserSummary[]>([]);
  protected readonly inactiveGuests = signal<ParticipantSummary[]>([]);
  protected readonly participants = signal<ParticipantSummary[]>([]);
  protected readonly guestName = new FormControl('', { nonNullable: true });
  protected readonly guestNotice = signal('');
  private guestRetry: { displayName: string; operationId: string } | null = null;
  protected readonly guestUncertain = signal(false);
  protected readonly joinLink = signal('');
  private joinLinkId = '';
  protected readonly linkUncertain = signal(false);
  protected readonly linkStatusReviewed = signal(false);
  protected readonly canReplaceLink = computed(
    () =>
      this.linkStatusReviewed() &&
      !this.invitationRows().some((i) => i.kind === 'LINK' && i.status === 'PENDING'),
  );
  protected readonly invitationsOpen = signal(false);
  protected readonly invitationsLoading = signal(false);
  protected readonly invitationRows = signal<InvitationStatus[]>([]);
  protected readonly invitationNotice = signal('');
  protected readonly invitationReadError = signal('');
  protected readonly inviteEmail = new FormControl('', { nonNullable: true });
  protected readonly inviteParticipant = signal('');
  protected readonly emailUncertain = signal(false);
  private emailRetry: EmailInvitationRequest | null = null;
  protected readonly email = new FormControl('', { nonNullable: true });
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly membersLoading = signal(false);
  protected readonly membersFailed = signal(false);

  constructor() {
    this.destroyRef.onDestroy(() => {
      if (this.session.version === this.sessionVersion) this.modal.setMutationPending(false);
    });
    this.session.changes.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.joinLink.set('');
      this.joinLinkId = '';
      this.emailRetry = null;
      this.guestRetry = null;
      this.invitationRows.set([]);
      this.participants.set([]);
      this.inactiveGuests.set([]);
      this.members.set([]);
      this.inviteEmail.reset();
      this.guestName.reset();
      this.invitationNotice.set('Your session changed. Reopen this group.');
    });
    this.dashboard.refreshes.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((force) => {
      if (!force && this.invitationsOpen() && !this.saving()) {
        this.retryMembers();
        this.readInvitations();
      }
    });
    this.destroyRef.onDestroy(() => {
      this.joinLink.set('');
      this.joinLinkId = '';
      this.emailRetry = null;
      this.guestRetry = null;
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
          this.participants.set(financialRoster(g));
          this.inactiveGuests.set(g.inactiveGuests ?? []);
          this.membersLoading.set(false);
        },
        error: () => {
          this.membersLoading.set(false);
          this.membersFailed.set(true);
          this.error.set('Could not load group members.');
        },
      });
  }

  createGuest(retry = false): void {
    if (
      this.session.version !== this.sessionVersion ||
      !this.groupId ||
      this.saving() ||
      this.membersLoading()
    )
      return;
    const request = retry
      ? this.guestRetry
      : { displayName: this.guestName.value.trim(), operationId: crypto.randomUUID() };
    if (!request?.displayName || (!retry && this.guestUncertain())) return;
    this.guestRetry = request;
    this.saving.set(true);
    this.modal.setMutationPending(true);
    this.groups
      .createGuest(this.groupId, request.displayName, request.operationId)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (guest) => {
          this.participants.update((rows) => [...rows.filter((p) => p.id !== guest.id), guest]);
          this.guestName.reset();
          this.guestRetry = null;
          this.guestUncertain.set(false);
          this.guestNotice.set(
            'Guest created. They have financial entries only, without account access.',
          );
          this.saving.set(false);
          this.modal.setMutationPending(false);
          this.dashboard.refresh();
        },
        error: (err) => {
          this.guestUncertain.set(true);
          this.guestNotice.set(
            (err?.error?.message ?? 'Could not create the guest.') +
              ' The guest may already exist. Retry guest creation with its original identity or review the roster.',
          );
          this.saving.set(false);
          this.modal.setMutationPending(false);
        },
      });
  }

  removeGuest(participant: ParticipantSummary): void {
    if (
      this.session.version !== this.sessionVersion ||
      !this.groupId ||
      this.saving() ||
      participant.accountId !== null
    )
      return;
    this.saving.set(true);
    this.modal.setMutationPending(true);
    this.groups
      .removeGuest(this.groupId, participant.id)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.participants.update((rows) => rows.filter((p) => p.id !== participant.id));
          this.inactiveGuests.update((rows) => [
            ...rows.filter((p) => p.id !== participant.id),
            participant,
          ]);
          this.invitationRows.update((rows) =>
            rows.map((i) =>
              i.participantId === participant.id && i.status === 'PENDING'
                ? { ...i, status: 'REVOKED' }
                : i,
            ),
          );
          if (this.inviteParticipant() === participant.id) this.inviteParticipant.set('');
          this.guestNotice.set(
            'Guest removed from new entries. Existing financial history is retained.',
          );
          this.saving.set(false);
          this.modal.setMutationPending(false);
          this.dashboard.refresh();
        },
        error: (err) => {
          this.guestNotice.set(
            (err?.error?.message ?? 'Could not confirm guest removal.') +
              ' Review the roster and balances before trying again.',
          );
          this.saving.set(false);
          this.modal.setMutationPending(false);
        },
      });
  }

  reactivateGuest(participant: ParticipantSummary): void {
    if (
      this.session.version !== this.sessionVersion ||
      !this.groupId ||
      this.saving() ||
      participant.accountId !== null
    )
      return;
    this.saving.set(true);
    this.modal.setMutationPending(true);
    this.groups
      .reactivateGuest(this.groupId, participant.id)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (active) => {
          this.inactiveGuests.update((rows) => rows.filter((p) => p.id !== active.id));
          this.participants.update((rows) => [...rows.filter((p) => p.id !== active.id), active]);
          this.guestNotice.set(
            'Guest re-added with the original identity. Historical entries can be reviewed or restored; earlier invitations stay revoked.',
          );
          this.saving.set(false);
          this.modal.setMutationPending(false);
          this.dashboard.refresh();
        },
        error: (err) => {
          this.guestNotice.set(
            (err?.error?.message ?? 'Guest re-add could not be confirmed.') +
              ' Refresh the roster before trying again.',
          );
          this.saving.set(false);
          this.modal.setMutationPending(false);
        },
      });
  }

  readInvitations(): void {
    if (
      this.session.version !== this.sessionVersion ||
      !this.groupId ||
      this.invitationsLoading() ||
      this.saving()
    )
      return;
    this.invitationsOpen.set(true);
    this.invitationsLoading.set(true);
    this.invitationReadError.set('');
    this.invitations
      .list(this.groupId)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          this.invitationRows.set(rows);
          this.linkStatusReviewed.set(true);
          this.invitationsLoading.set(false);
        },
        error: () => {
          this.invitationReadError.set(
            'Invitation status unavailable. Read status again before making another decision.',
          );
          this.invitationsLoading.set(false);
        },
      });
  }

  sendInvitation(retry = false, resend = false): void {
    if (
      this.session.version !== this.sessionVersion ||
      !this.groupId ||
      this.saving() ||
      this.invitationsLoading()
    )
      return;
    const request = retry
      ? this.emailRetry
      : {
          email: this.inviteEmail.value.trim(),
          ...(this.inviteParticipant() ? { participantId: this.inviteParticipant() } : {}),
          operationId: crypto.randomUUID(),
          ...(resend ? { resend: true } : {}),
        };
    if (!request?.email || (!retry && this.emailUncertain())) return;
    this.emailRetry = request;
    this.saving.set(true);
    this.modal.setMutationPending(true);
    this.invitations
      .email(this.groupId, request)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (row) => {
          this.invitationRows.update((rows) => [row, ...rows.filter((i) => i.id !== row.id)]);
          this.emailUncertain.set(false);
          this.emailRetry = null;
          this.invitationNotice.set(
            'Invitation sent; acceptance is pending. This does not add an account or claim guest entries.',
          );
          this.saving.set(false);
          this.modal.setMutationPending(false);
        },
        error: (err) => {
          this.emailUncertain.set(true);
          this.invitationNotice.set(
            err?.status === 503
              ? 'Email delivery unavailable. No delivery or membership is confirmed. Retry the original invitation or review status.'
              : (err?.error?.message ?? 'Invitation outcome uncertain.') +
                  ' Review status or retry the original invitation.',
          );
          this.saving.set(false);
          this.modal.setMutationPending(false);
        },
      });
  }

  reviewSeparateEmail(): void {
    if (this.session.version !== this.sessionVersion || this.saving() || !this.linkStatusReviewed())
      return;
    if (
      !window.confirm(
        'Review invitation status first. The previous email may have been sent. Start a separate invitation with a new operation identity?',
      )
    )
      return;
    this.emailRetry = null;
    this.emailUncertain.set(false);
    this.invitationNotice.set(
      'Review the recipient and guest before sending. Resend deliberately invalidates the earlier email link.',
    );
  }

  generateLink(): void {
    if (
      this.session.version !== this.sessionVersion ||
      !this.groupId ||
      this.saving() ||
      this.invitationsLoading() ||
      this.linkUncertain()
    )
      return;
    this.joinLink.set('');
    this.joinLinkId = '';
    this.saving.set(true);
    this.modal.setMutationPending(true);
    this.invitations
      .link(this.groupId)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.invitationRows.update((rows) => [result.invitation, ...rows]);
          let url: URL | null = null;
          try {
            url = new URL(result.url, location.origin);
          } catch {
            /* malformed responses require status review */
          }
          if (
            url &&
            url.origin === location.origin &&
            url.pathname === '/invitation' &&
            !url.search &&
            /^#action=group-invitation&token=[A-Za-z0-9_-]{43}$/.test(url.hash)
          ) {
            this.joinLink.set(url.href);
            this.joinLinkId = result.invitation.id;
            this.invitationNotice.set(
              'Join link created. Copy it now; it cannot be recovered later. It grants one join opportunity and never claims guest entries. Forwarding transfers that opportunity.',
            );
          } else {
            this.linkUncertain.set(true);
            this.linkStatusReviewed.set(false);
            this.invitationNotice.set(
              'The link could not be displayed safely. Read status and revoke it before creating a replacement.',
            );
          }
          this.saving.set(false);
          this.modal.setMutationPending(false);
        },
        error: () => {
          this.linkUncertain.set(true);
          this.linkStatusReviewed.set(false);
          this.invitationNotice.set(
            'The join link may already exist, but its secret cannot be recovered. Read invitation status, revoke any unknown pending link, then deliberately create a replacement.',
          );
          this.saving.set(false);
          this.modal.setMutationPending(false);
        },
      });
  }

  replaceLostLink(): void {
    if (
      !this.linkStatusReviewed() ||
      this.invitationRows().some((i) => i.kind === 'LINK' && i.status === 'PENDING')
    )
      return;
    this.linkUncertain.set(false);
    this.generateLink();
  }

  async copyLink(): Promise<void> {
    const link = this.joinLink();
    const version = this.session.version;
    if (!link || version !== this.sessionVersion) return;
    try {
      await navigator.clipboard.writeText(link);
      if (!this.destroyRef.destroyed && this.session.version === version)
        this.invitationNotice.set(
          'Join link copied. Share it deliberately; forwarding transfers its one join opportunity.',
        );
    } catch {
      if (!this.destroyRef.destroyed && this.session.version === version)
        this.invitationNotice.set(
          'Copy was unavailable. Select and copy the displayed link before closing this panel.',
        );
    }
  }

  revokeInvitation(row: InvitationStatus): void {
    if (
      this.session.version !== this.sessionVersion ||
      !this.groupId ||
      this.saving() ||
      row.status !== 'PENDING'
    )
      return;
    this.saving.set(true);
    this.modal.setMutationPending(true);
    this.invitations
      .revoke(this.groupId, row.id)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.invitationRows.update((rows) =>
            rows.map((i) => (i.id === row.id ? { ...i, status: 'REVOKED' } : i)),
          );
          if (row.id === this.joinLinkId) {
            this.joinLink.set('');
            this.joinLinkId = '';
          }
          this.invitationNotice.set(
            'Invitation revoked. Accepted ownership is never undone by revocation.',
          );
          this.saving.set(false);
          this.modal.setMutationPending(false);
        },
        error: (err) => {
          this.invitationNotice.set(
            (err?.error?.message ?? 'Revocation could not be confirmed.') +
              ' Read invitation status before another action.',
          );
          this.saving.set(false);
          this.modal.setMutationPending(false);
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

  canAdd(): boolean {
    return !!this.email.value.trim() && !!this.groupId && !this.saving() && !this.membersLoading();
  }

  add(): void {
    if (this.session.version !== this.sessionVersion) return;
    if (!this.groupId || !this.canAdd()) return;
    const email = this.email.value.trim();
    this.saving.set(true);
    this.modal.setMutationPending(true);
    this.error.set(null);
    this.groups
      .addMember(this.groupId, email)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (g) => {
          this.members.set(g.members);
          this.participants.set(financialRoster(g));
          this.inactiveGuests.set(g.inactiveGuests ?? []);
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
