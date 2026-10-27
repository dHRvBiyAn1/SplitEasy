import {
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  afterRenderEffect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subject, Subscription, takeUntil } from 'rxjs';
import { FriendDetail, FriendSummary } from './friend.models';
import { FriendService } from './friend.service';
import { DashboardService } from '../dashboard/dashboard.service';
import { SessionService } from '../core/auth/session.service';
import { AuthService } from '../core/auth/auth.service';
import { GroupService } from '../groups/group.service';
import { financialRoster, GroupSummary, ownedIdentities } from '../groups/group.models';
import { ParticipantSummary } from '../groups/participant.models';
import { MoneyComponent } from '../shared/money.component';
@Component({
  selector: 'app-friends',
  imports: [RouterLink, MoneyComponent],
  templateUrl: './friends.component.html',
  styleUrl: './friends.component.scss',
})
export class FriendsComponent {
  private readonly dashboard = inject(DashboardService);
  private loadRequest?: Subscription;
  private readonly session = inject(SessionService);
  private readonly scopeChanges = new Subject<void>();
  private readonly sourceField = viewChild<ElementRef<HTMLSelectElement>>('sourceField');
  private readonly confirmation = viewChild<ElementRef<HTMLElement>>('confirmation');
  private removeTrigger: HTMLElement | null = null;
  private readonly groupsApi = inject(GroupService);
  private readonly auth = inject(AuthService);
  private readonly service = inject(FriendService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly friends = signal<FriendSummary[]>([]);
  protected readonly detail = signal<FriendDetail | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected id = '';
  constructor() {
    afterRenderEffect(() => this.sourceField()?.nativeElement.focus());
    afterRenderEffect(() => this.confirmation()?.nativeElement.focus());
    this.dashboard.refreshes.pipe(takeUntilDestroyed()).subscribe(() => this.foreground());
    this.session.changes.pipe(takeUntilDestroyed()).subscribe(() => {
      this.cancelAdd();
      this.friends.set([]);
      this.detail.set(null);
      this.removing.set(null);
      this.removeTrigger = null;
      this.deleting.set(false);
      this.saving.set(false);
      this.writeError.set(null);
      this.needsReview.set(false);
      this.notice.set(null);
      this.loading.set(false);
      this.error.set('Session changed. Try again to load saved friends for the current account.');
    });
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.scopeChanges.next();
      this.cancelAdd();
      this.removing.set(null);
      this.removeTrigger = null;
      this.deleting.set(false);
      this.saving.set(false);
      this.writeError.set(null);
      this.needsReview.set(false);
      this.notice.set(null);
      this.id = params.get('id') ?? '';
      this.loading.set(false);
      this.load();
    });
  }
  @HostListener('window:focus')
  @HostListener('document:visibilitychange')
  protected foreground() {
    if (
      document.visibilityState === 'visible' &&
      this.auth.isAuthenticated() &&
      !this.needsReview()
    )
      this.load();
  }
  protected load(review = false, force = false) {
    if (
      this.saving() ||
      this.deleting() ||
      (!force && this.loading()) ||
      !this.auth.isAuthenticated()
    )
      return;
    this.loadRequest?.unsubscribe();
    this.loading.set(true);
    this.error.set(null);
    this.friends.set([]);
    this.detail.set(null);
    const request: Observable<FriendDetail | FriendSummary[]> = this.id
      ? this.service.get(this.id)
      : this.service.list();
    this.loadRequest = request
      .pipe(
        takeUntil(this.scopeChanges),
        takeUntil(this.session.changes),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (v) => {
          if (Array.isArray(v)) this.friends.set(v);
          else this.detail.set(v);
          this.loading.set(false);
          if (review) {
            this.needsReview.set(false);
            this.writeError.set(null);
          }
        },
        error: (err) => {
          this.loading.set(false);
          this.error.set(
            this.id && (err.status === 404 || err.status === 403)
              ? 'Saved friend unavailable. Return to your directory and review current access.'
              : 'Could not load saved friends. Try again to review current balances.',
          );
        },
      });
  }
  private groupsRequest?: Subscription;
  private peopleRequest?: Subscription;
  protected readonly adding = signal(false);
  protected readonly groups = signal<GroupSummary[]>([]);
  protected readonly people = signal<ParticipantSummary[]>([]);
  protected readonly sourceLoading = signal(false);
  protected readonly sourceError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected groupId = '';
  protected participantId = '';
  protected startAdd() {
    this.adding.set(true);
    this.sourceLoading.set(true);
    this.sourceError.set(null);
    this.groupsRequest = this.groupsApi
      .listMyGroups()
      .pipe(
        takeUntil(this.scopeChanges),
        takeUntil(this.session.changes),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (groups) => {
          this.groups.set(groups);
          this.sourceLoading.set(false);
        },
        error: () => {
          this.sourceLoading.set(false);
          this.sourceError.set(
            'Could not load your groups. Cancel and reopen Add friend to try again.',
          );
        },
      });
  }
  protected chooseGroup(id: string) {
    this.peopleRequest?.unsubscribe();
    this.groupId = id;
    this.participantId = '';
    this.people.set([]);
    this.sourceError.set(null);
    this.sourceLoading.set(false);
    if (!id) return;
    this.sourceLoading.set(true);
    this.peopleRequest = this.groupsApi
      .getGroup(id)
      .pipe(
        takeUntil(this.scopeChanges),
        takeUntil(this.session.changes),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (group) => {
          const accountId = this.auth.user()?.id ?? '';
          const owned = ownedIdentities(group, accountId);
          this.people.set(
            financialRoster(group).filter(
              (person) =>
                person.accountId !== accountId &&
                !owned.includes(person.id) &&
                person.canonicalParticipantId !== group.viewerParticipantId,
            ),
          );
          this.sourceLoading.set(false);
        },
        error: () => {
          this.sourceLoading.set(false);
          this.sourceError.set(
            'Could not load this group. Select the group again to review its current people.',
          );
        },
      });
  }
  protected cancelAdd() {
    this.groupsRequest?.unsubscribe();
    this.peopleRequest?.unsubscribe();
    this.adding.set(false);
    this.groups.set([]);
    this.people.set([]);
    this.groupId = '';
    this.participantId = '';
    this.sourceError.set(null);
    this.sourceLoading.set(false);
  }
  protected save() {
    if (
      this.saving() ||
      this.needsReview() ||
      this.sourceLoading() ||
      !this.groupId ||
      !this.people().some((p) => p.id === this.participantId && p.accountId)
    )
      return;
    this.saving.set(true);
    this.sourceError.set(null);
    this.service
      .save(this.groupId, this.participantId)
      .pipe(
        takeUntil(this.scopeChanges),
        takeUntil(this.session.changes),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.cancelAdd();
          this.notice.set('Friend saved.');
          this.load(false, true);
        },
        error: (err) => {
          this.saving.set(false);
          if (!err.status || err.status >= 500) {
            this.needsReview.set(true);
            this.writeError.set(
              'Could not confirm saving. Saved status may have changed. Check saved status before trying again.',
            );
          } else
            this.sourceError.set(
              'Could not save this friend. Select the current group again and review eligible people.',
            );
        },
      });
  }
  protected readonly removing = signal<FriendSummary | null>(null);
  protected readonly deleting = signal(false);
  protected readonly writeError = signal<string | null>(null);
  protected readonly needsReview = signal(false);
  protected requestRemove(friend: FriendSummary, event: Event) {
    this.removeTrigger = event.currentTarget as HTMLElement;
    this.removing.set(friend);
  }
  protected cancelRemove() {
    this.removing.set(null);
    this.removeTrigger?.focus();
    this.removeTrigger = null;
  }
  protected remove() {
    const friend = this.removing();
    if (!friend || this.deleting() || this.needsReview()) return;
    this.deleting.set(true);
    this.writeError.set(null);
    this.service
      .remove(friend.id)
      .pipe(
        takeUntil(this.scopeChanges),
        takeUntil(this.session.changes),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => {
          this.deleting.set(false);
          this.removing.set(null);
          this.notice.set('Saved friend removed.');
          if (this.id) void this.router.navigateByUrl('/friends');
          else this.load(false, true);
        },
        error: () => {
          this.deleting.set(false);
          this.removing.set(null);
          this.needsReview.set(true);
          this.writeError.set(
            'Could not confirm removal. Saved status may have changed. Check saved status before trying again.',
          );
        },
      });
  }
  protected review() {
    this.cancelAdd();
    if (!this.id) {
      this.load(true);
      return;
    }
    if (this.loading()) return;
    this.loading.set(true);
    this.error.set(null);
    this.detail.set(null);
    this.loadRequest = this.service
      .list()
      .pipe(
        takeUntil(this.scopeChanges),
        takeUntil(this.session.changes),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (friends) => {
          this.loading.set(false);
          if (!friends.some((friend) => friend.id === this.id)) {
            void this.router.navigateByUrl('/friends');
            return;
          }
          this.needsReview.set(false);
          this.writeError.set(null);
          this.load();
        },
        error: () => {
          this.loading.set(false);
          this.error.set('Could not load saved friends. Try checking saved status again.');
        },
      });
  }
  protected status(friend: FriendSummary): string {
    if (!friend.sharedGroupCount || !friend.balances.length) return 'No current shared group data';
    if (!friend.hasOpenObligations) return 'All square in current shared groups';
    return friend.balances.every((b) => b.netMinor === 0)
      ? 'Even overall · open group obligations'
      : 'Open balances in current shared groups';
  }
}
