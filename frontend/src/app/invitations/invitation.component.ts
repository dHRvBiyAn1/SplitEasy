import { GroupService } from '../groups/group.service';
import { takeUntil } from 'rxjs';
import { MoneyComponent } from '../shared/money.component';
import { DashboardService } from '../dashboard/dashboard.service';
import { InvitationService } from './invitation.service';
import { InvitationPreview, invitationDate } from './invitation.models';
import { Component, DestroyRef, computed, HostListener, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';

@Component({
  selector: 'app-invitation',
  imports: [RouterLink, MoneyComponent],
  styleUrl: '../auth/auth.scss',
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    .auth__form-wrap {
      min-width: 0;
      grid-template-columns: minmax(0, 1fr);
      overflow-wrap: anywhere;
    }
    .invitation-balances {
      min-width: 0;
      max-width: 100%;
      overflow-x: auto;
    }
    table {
      width: 100%;
      border-collapse: collapse;
    }
    th,
    td {
      overflow-wrap: normal;
      padding: var(--sp-2);
      text-align: left;
      border-bottom: 1px solid var(--line);
    }
    caption {
      text-align: left;
      margin-bottom: var(--sp-3);
    }
  `,
  template: `<div class="auth__panel">
    <main class="auth__form-wrap">
      <h1 class="dc-display auth__title">Group invitation</h1>
      @if (error()) {
        <p class="auth__error" role="alert">{{ error() }}</p>
      }
      @if (message()) {
        <p role="status">{{ message() }}</p>
        <a [routerLink]="['/groups', acceptedGroup()]">Open group</a>
      }
      @if (!user()) {
        <p>
          Sign in or register, verify your email if this is an email invitation, then reopen the
          original invitation. The secret stays only on this page and is cleared when your session
          changes.
        </p>
        <p><a routerLink="/login">Sign in</a> · <a routerLink="/register">Register</a></p>
      } @else if (valid() && !message()) {
        <p>Signed in as {{ user()!.displayName }} ({{ user()!.email }}).</p>
        <p>Preview the invitation before choosing to join. Opening this page does not accept it.</p>
        <button
          type="button"
          class="dc-btn-accent"
          (click)="readPreview()"
          [disabled]="pending() || uncertain()"
        >
          Preview invitation
        </button>
        @if (preview(); as p) {
          <h2>{{ p.groupName }}</h2>
          <p>
            Expires <time [attr.datetime]="p.expiresAt">{{ localDate(p.expiresAt) }}</time> (local
            time). Balances can change; this is a review snapshot.
          </p>
          @if (needsOwnership()) {
            <p>
              Accepting links {{ p.participant?.displayName }}'s existing financial entries to your
              account. This cannot be undone here. Original entries and split identities stay in
              history.
            </p>
            <div
              class="invitation-balances"
              tabindex="0"
              role="region"
              aria-label="Combined net balances by currency"
            >
              <table>
                <caption>
                  Combined net position by currency. Positive means owed; negative means owing. Zero
                  net can still have direct obligations.
                </caption>
                <thead>
                  <tr>
                    <th scope="col">Currency</th>
                    <th scope="col">Your current net</th>
                    <th scope="col">Guest net</th>
                    <th scope="col">Combined net</th>
                  </tr>
                </thead>
                <tbody>
                  @for (b of p.balances; track b.currency) {
                    <tr>
                      <th scope="row">{{ b.currency }}</th>
                      <td>
                        <app-money
                          [minor]="b.existingNetMinor"
                          [currency]="b.currency"
                          [net]="true"
                        />
                      </td>
                      <td>
                        <app-money
                          [minor]="b.participantNetMinor"
                          [currency]="b.currency"
                          [net]="true"
                        />
                      </td>
                      <td>
                        <app-money
                          [minor]="b.resultingNetMinor"
                          [currency]="b.currency"
                          [net]="true"
                        />
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            <label
              ><input
                id="confirm-ownership"
                type="checkbox"
                [checked]="ownershipConfirmed()"
                (change)="ownershipConfirmed.set($any($event.target).checked)"
              />
              I accept irreversible ownership of this guest's financial entries, including their
              obligations.</label
            >
          } @else {
            <p>This joins the group using your own identity. It never claims a guest's entries.</p>
          }
          <button
            type="button"
            class="dc-btn-accent"
            (click)="accept()"
            [disabled]="pending() || uncertain() || (needsOwnership() && !ownershipConfirmed())"
          >
            {{ needsOwnership() ? 'Claim guest and join' : 'Join group' }}
          </button>
        }
        @if (uncertain()) {
          <p role="status">
            Acceptance may already have succeeded. Another acceptance is blocked. Read current group
            status to review the outcome.
          </p>
          <button
            type="button"
            class="dc-btn-outline"
            (click)="readCurrentStatus()"
            [disabled]="pending()"
          >
            Read current group status
          </button>
        }
        <p>
          <a routerLink="/profile">Account security and verification</a> ·
          <a routerLink="/login"
            >Sign in with another account, then reopen the original invitation</a
          >
        </p>
      }
    </main>
  </div>`,
})
export class InvitationComponent {
  protected readonly localDate = invitationDate;
  private readonly groups = inject(GroupService);
  protected readonly uncertain = signal(false);
  private readonly invitations = inject(InvitationService);
  private readonly dashboard = inject(DashboardService);
  protected readonly preview = signal<InvitationPreview | null>(null);
  protected readonly ownershipConfirmed = signal(false);
  protected readonly needsOwnership = computed(
    () => !!this.preview()?.participant || !!this.preview()?.requiresOwnershipConfirmation,
  );
  protected readonly message = signal('');
  protected readonly acceptedGroup = signal('');
  private readonly auth = inject(AuthService);
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly user = this.auth.user;
  protected readonly valid = signal(false);
  protected readonly pending = signal(false);
  protected readonly error = signal('');
  private token = '';

  constructor() {
    this.consumeFragment();
    const reopen = () => this.consumeFragment();
    window.addEventListener('hashchange', reopen);
    this.router.events.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((event) => {
      // The router can restore a fragment already consumed by the native hash handler.
      if (event instanceof NavigationEnd && location.hash)
        history.replaceState(history.state, '', location.pathname + location.search);
    });
    this.session.changes.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.token = '';
      this.valid.set(false);
      this.pending.set(false);
      this.uncertain.set(false);
      this.preview.set(null);
      this.ownershipConfirmed.set(false);
      this.message.set('');
      this.acceptedGroup.set('');
      this.error.set('Your session changed. Reopen the original invitation to continue.');
    });
    this.destroyRef.onDestroy(() => {
      window.removeEventListener('hashchange', reopen);
      this.token = '';
      this.preview.set(null);
    });
  }

  private consumeFragment(): void {
    const fragment = new URLSearchParams(location.hash.slice(1));
    history.replaceState(history.state, '', location.pathname + location.search);
    if (this.pending() || this.uncertain()) {
      this.error.set(
        'An invitation action is pending. Wait for its result before reopening a link.',
      );
      return;
    }
    this.token = '';
    this.valid.set(false);
    this.error.set('');
    this.preview.set(null);
    this.message.set('');
    this.ownershipConfirmed.set(false);
    const token = fragment.get('token') ?? '';
    if (
      fragment.getAll('action').length === 1 &&
      fragment.getAll('token').length === 1 &&
      [...fragment.keys()].length === 2 &&
      fragment.get('action') === 'group-invitation' &&
      /^[A-Za-z0-9_-]{43}$/.test(token)
    ) {
      this.token = token;
      this.valid.set(true);
    } else this.error.set('Reopen the original invitation, or ask a group member for a new link.');
  }
  readPreview(): void {
    const owner = this.user()?.id;
    const version = this.session.version;
    if (!owner || !this.valid() || this.pending() || this.uncertain()) return;
    this.pending.set(true);
    this.ownershipConfirmed.set(false);
    this.invitations
      .preview(this.token)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (preview) => {
          if (this.user()?.id !== owner || this.session.version !== version) return;
          this.preview.set(preview);
          this.pending.set(false);
          this.error.set('');
        },
        error: (err) => {
          this.preview.set(null);
          this.pending.set(false);
          this.error.set(
            err.status === 429
              ? 'Too many attempts. Wait before previewing again.'
              : 'Invitation unavailable. Use the verified matching account for an email invitation, then reopen the original link. It may also be expired, revoked or already used.',
          );
        },
      });
  }

  accept(): void {
    const preview = this.preview();
    const owner = this.user()?.id;
    const version = this.session.version;
    if (
      !preview ||
      !owner ||
      !this.valid() ||
      this.pending() ||
      this.uncertain() ||
      (this.needsOwnership() && !this.ownershipConfirmed())
    )
      return;
    this.pending.set(true);
    this.invitations
      .accept(this.token, this.needsOwnership())
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          if (
            this.user()?.id !== owner ||
            this.session.version !== version ||
            result.accountId !== owner
          )
            return;
          this.token = '';
          this.preview.set(null);
          this.valid.set(false);
          this.pending.set(false);
          this.acceptedGroup.set(result.groupId);
          this.message.set(
            result.claimed
              ? 'Ownership accepted. Your account now owns the original guest entries.'
              : 'You joined the group. No guest entries were claimed.',
          );
          this.dashboard.refresh();
        },
        error: () => {
          this.pending.set(false);
          this.uncertain.set(true);
          this.error.set(
            'Acceptance could not be confirmed. Read current group status; do not send another acceptance.',
          );
        },
      });
  }

  @HostListener('window:focus')
  @HostListener('document:visibilitychange')
  revalidateVisible(): void {
    if (
      document.visibilityState === 'visible' &&
      this.preview() &&
      !this.uncertain() &&
      !this.message()
    )
      this.readPreview();
  }

  readCurrentStatus(): void {
    const preview = this.preview();
    const owner = this.user()?.id;
    const version = this.session.version;
    if (!preview || !owner || !this.uncertain() || this.pending()) return;
    this.pending.set(true);
    this.groups
      .getGroup(preview.groupId)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (group) => {
          if (this.user()?.id !== owner || this.session.version !== version) return;
          this.pending.set(false);
          if (preview.participant && !group.ownedParticipantIds?.includes(preview.participant.id)) {
            this.error.set(
              'Your membership exists, but guest ownership is not confirmed. Acceptance remains blocked. Ask a group member to review invitation status, or reopen the original invitation after reviewing the outcome.',
            );
            return;
          }
          this.token = '';
          this.valid.set(false);
          this.preview.set(null);
          this.uncertain.set(false);
          this.error.set('');
          this.acceptedGroup.set(group.id);
          this.message.set(
            preview.participant
              ? 'Ownership confirmed by your current owned financial identities.'
              : 'Current group access confirmed. No guest entries were claimed; invitation consumption was not confirmed by this read.',
          );
          this.dashboard.refresh();
        },
        error: () => {
          this.pending.set(false);
          this.error.set(
            'Current membership and guest ownership could not be confirmed. No further acceptance will be sent here. Ask a group member to review invitation status before reopening the original invitation.',
          );
        },
      });
  }
}
