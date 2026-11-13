import { Component, DestroyRef, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subject, takeUntil } from 'rxjs';
import { SessionService } from '../core/auth/session.service';
import { Currency, amountToMinor, minorToDisplay } from '../shared/currency';
import { Frequency, RecurringSchedule } from './recurring.models';
import { RecurringService } from './recurring.service';
import { TranslatePipe } from '../core/i18n/translate.pipe';

const FREQUENCY_LABEL: Record<Frequency, string> = {
  DAILY: 'Every day',
  WEEKLY: 'Every week',
  MONTHLY: 'Every month',
};

/** A group's recurring expenses: change future occurrences, cancel, and see why one halted. */
@Component({
  selector: 'app-recurring',
  imports: [TranslatePipe, RouterLink],
  templateUrl: './recurring.component.html',
  styleUrl: './recurring.component.scss',
})
export class RecurringComponent {
  private readonly service = inject(RecurringService);
  private readonly session = inject(SessionService);
  private readonly route = inject(ActivatedRoute);
  private readonly scope = new Subject<void>();

  protected groupId = '';
  protected readonly schedules = signal<RecurringSchedule[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly editing = signal<RecurringSchedule | null>(null);
  protected readonly frequencies = Object.keys(FREQUENCY_LABEL) as Frequency[];
  protected readonly frequencyLabel = FREQUENCY_LABEL;
  protected description = '';
  protected amount = '';
  protected frequency: Frequency = 'MONTHLY';

  constructor() {
    const destroyRef = inject(DestroyRef);
    destroyRef.onDestroy(() => this.scope.next());
    this.session.changes.pipe(takeUntilDestroyed()).subscribe(() => {
      this.scope.next();
      this.schedules.set([]);
      this.editing.set(null);
      this.error.set('Session changed. Reload to see recurring expenses.');
    });
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.scope.next();
      this.groupId = params.get('id') ?? '';
      this.editing.set(null);
      this.load();
    });
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.service
      .list(this.groupId)
      .pipe(takeUntil(this.scope))
      .subscribe({
        next: (list) => {
          this.schedules.set(list);
          this.loading.set(false);
        },
        error: () => {
          this.error.set('Could not load recurring expenses.');
          this.loading.set(false);
        },
      });
  }

  protected amountText(s: RecurringSchedule): string {
    const currency: Currency = s.template.currency ?? 'USD';
    return `${currency} ${minorToDisplay(s.template.amountMinor ?? s.template.amountCents ?? 0, currency)}`;
  }

  /** Splits other than EQUAL carry typed values that would no longer match a new total. */
  protected canEditAmount(s: RecurringSchedule): boolean {
    return (s.template.splitType ?? 'EQUAL') === 'EQUAL' && !s.template.contributions?.length;
  }

  protected edit(s: RecurringSchedule): void {
    this.editing.set(s);
    this.description = s.template.description;
    this.amount = minorToDisplay(
      s.template.amountMinor ?? s.template.amountCents ?? 0,
      s.template.currency ?? 'USD',
    );
    this.frequency = s.frequency;
    this.error.set(null);
  }

  protected saveEdit(): void {
    const s = this.editing();
    if (!s || !this.description.trim()) return;
    const template = { ...s.template, description: this.description.trim() };
    if (this.canEditAmount(s)) {
      const minor = amountToMinor(this.amount, s.template.currency ?? 'USD', false);
      if (!Number.isSafeInteger(minor) || minor <= 0) {
        this.error.set('Enter a valid positive amount.');
        return;
      }
      template.amountMinor = minor;
      template.currency = s.template.currency ?? 'USD';
      delete template.amountCents;
    }
    this.run(
      this.service.update(this.groupId, s.id, {
        template,
        frequency: this.frequency,
        anchorDate: s.anchorDate,
        timeZone: s.timeZone,
        expectedVersion: s.version,
      }),
      'Could not save. Reload and try again if it changed elsewhere.',
    );
  }

  protected cancel(s: RecurringSchedule): void {
    this.run(this.service.cancel(this.groupId, s.id), 'Could not cancel this schedule.');
  }

  private run(call: Observable<RecurringSchedule>, failure: string): void {
    this.error.set(null);
    call.pipe(takeUntil(this.scope)).subscribe({
      next: (saved) => {
        this.schedules.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
        this.editing.set(null);
      },
      error: (err) => this.error.set(err?.error?.message ?? failure),
    });
  }
}
