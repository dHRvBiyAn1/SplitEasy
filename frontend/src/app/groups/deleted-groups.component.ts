import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { Observable, Subject, takeUntil } from 'rxjs';
import { RouterLink } from '@angular/router';
import { SessionService } from '../core/auth/session.service';
import { DashboardService } from '../dashboard/dashboard.service';
import { GroupArtComponent } from '../shared/group-art.component';
import { GroupService } from './group.service';
import { GroupSummary } from './group.models';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { translate } from '../core/i18n/i18n.service';

/**
 * The creator's recovery list for groups they deleted. Deletion keeps every expense, payment and
 * revision, so restoring is a decision rather than a repair; the stored lifecycle version is the
 * precondition, which keeps a stale screen from resurrecting a group whose state has moved on.
 */
@Component({
  selector: 'app-deleted-groups',
  imports: [TranslatePipe, RouterLink, GroupArtComponent],
  templateUrl: './deleted-groups.component.html',
  styleUrl: './deleted-groups.component.scss',
})
export class DeletedGroupsComponent implements OnInit {
  private readonly groups = inject(GroupService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly session = inject(SessionService);
  private readonly dashboard = inject(DashboardService);
  private readonly reloads = new Subject<void>();

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly rows = signal<GroupSummary[]>([]);
  protected readonly restoring = signal<string | null>(null);

  /** End callbacks whose account or component no longer owns them. */
  private scoped<T>(request: Observable<T>): Observable<T> {
    return request.pipe(
      takeUntil(this.reloads),
      takeUntil(this.session.changes),
      takeUntilDestroyed(this.destroyRef),
    );
  }

  constructor() {
    this.session.changes.pipe(takeUntilDestroyed()).subscribe(() => {
      this.rows.set([]);
      this.error.set(null);
      this.restoring.set(null);
      this.loading.set(false);
    });
  }

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.reloads.next();
    this.loading.set(true);
    this.error.set(null);
    this.scoped(this.groups.listDeletedGroups()).subscribe({
      next: (rows) => {
        this.rows.set(rows);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(translate('deletedGroups.couldNotLoadYour'));
        this.loading.set(false);
      },
    });
  }

  restore(group: GroupSummary): void {
    if (this.restoring()) return;
    this.restoring.set(group.id);
    this.error.set(null);
    this.scoped(this.groups.restoreGroup(group.id, group.lifecycleVersion ?? 0)).subscribe({
      next: () => {
        // Drop just the restored row; any other deleted group stays restorable.
        this.rows.update((rows) => rows.filter((r) => r.id !== group.id));
        this.restoring.set(null);
        this.dashboard.refresh();
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? translate('deletedGroups.couldNotRestoreThat'));
        this.restoring.set(null);
      },
    });
  }
}
