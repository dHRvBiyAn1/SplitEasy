import {
  CURRENCY_EXPONENT,
  MAX_TRANSACTION_MINOR,
  amountToMinor,
  minorToDisplay,
} from '../shared/currency';
import { MoneyComponent } from '../shared/money.component';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DestroyRef, Component, computed, inject, signal } from '@angular/core';
import { takeUntil } from 'rxjs';
import { AuthService } from '../core/auth/auth.service';
import { SessionService } from '../core/auth/session.service';
import { DashboardService } from '../dashboard/dashboard.service';
import { Settlement } from '../dashboard/dashboard.models';
import {
  PendingWritesService,
  PendingWriteError,
  UNCERTAIN_WRITE,
} from '../core/pending-writes.service';
import { RecordPaymentRequest } from '../payments/payment.models';
import { PaymentService } from '../payments/payment.service';
import { avatarTint } from '../core/avatar';
import { ModalService } from './modal.service';
import { ModalShellComponent } from './modal-shell.component';

@Component({
  selector: 'app-settle-up-modal',
  imports: [ModalShellComponent, MoneyComponent],
  templateUrl: './settle-up-modal.component.html',
  styleUrl: './modals.scss',
})
export class SettleUpModalComponent {
  private readonly dashboard = inject(DashboardService);
  private readonly pendingWrites = inject(PendingWritesService);
  protected readonly draftBlocked = signal(false);
  protected readonly retryNotice = signal<string | null>(null);
  private readonly payments = inject(PaymentService);
  private readonly auth = inject(AuthService);
  private readonly modal = inject(ModalService);
  private readonly session = inject(SessionService);
  private readonly sessionVersion = this.session.version;
  private readonly destroyRef = inject(DestroyRef);

  protected readonly precision = computed(
    () => CURRENCY_EXPONENT[this.selected()?.currency ?? 'USD'],
  );
  protected readonly maximum = computed(() =>
    minorToDisplay(MAX_TRANSACTION_MINOR, this.selected()?.currency ?? 'USD'),
  );
  protected readonly step = computed(() =>
    this.precision() === 0 ? '1' : '0.' + '0'.repeat(this.precision() - 1) + '1',
  );

  /** The dashboard already projects the selected mode, including all settlement actions. */
  protected readonly settlements = computed<Settlement[]>(() => {
    const all = this.dashboard.data()?.settlements ?? [];
    const gid = this.modal.settleGroupId();
    return gid ? all.filter((s) => s.groupId === gid) : all;
  });
  protected readonly loading = this.dashboard.loading;
  protected readonly loadFailed = this.dashboard.error;
  protected readonly allSimplified = computed(() => {
    const gid = this.modal.settleGroupId();
    const groups = (this.dashboard.data()?.groups ?? []).filter((g) => !gid || g.id === gid);
    return groups.length > 0 && groups.every((g) => g.simplifyDebts);
  });

  protected readonly selected = signal<Settlement | null>(this.modal.settlePrefill());
  /** Editable amount for the selected row, in the obligation currency — settle in full or partially. */
  protected readonly amount = signal('');
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.destroyRef.onDestroy(() => {
      if (this.session.version === this.sessionVersion) this.modal.setMutationPending(false);
    });
    try {
      const pending = this.pendingWrites.read<Omit<RecordPaymentRequest, 'operationId'>>('payment');
      if (pending?.draft) {
        this.selected.set(pending.draft as Settlement);
        this.amount.set(
          minorToDisplay(pending.request.amountMinor, pending.request.currency ?? 'USD'),
        );
        this.retryNotice.set(UNCERTAIN_WRITE);
        return;
      }
    } catch (err) {
      this.draftBlocked.set(true);
      this.retryNotice.set(
        err instanceof PendingWriteError
          ? err.message
          : 'Cannot read the saved retry draft. Check history before starting a separate entry.',
      );
    }
    const prefill = this.modal.settlePrefill();
    if (prefill) {
      this.amount.set(minorToDisplay(Math.abs(prefill.netMinor), prefill.currency ?? 'USD'));
    }
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

  /** They owe me (net>0) → "Name owes you"; I owe them (net<0) → "You owe Name". */
  label(s: Settlement): string {
    const name = `${s.counterparty.displayName}${s.counterparty.email ? ` (${s.counterparty.email})` : ' (guest)'}`;
    return s.netMinor > 0 ? `${name} owes you` : `You owe ${name}`;
  }

  /** Confirm-step lead: who is paying whom, mirroring the row's direction. */
  payingLabel(s: Settlement): string {
    const name = `${s.counterparty.displayName}${s.counterparty.email ? ` (${s.counterparty.email})` : ' (guest)'}`;
    return s.netMinor > 0 ? `${name} is paying you` : `You are paying ${name}`;
  }

  select(s: Settlement): void {
    this.error.set(null);
    this.amount.set(minorToDisplay(Math.abs(s.netMinor), s.currency ?? 'USD'));
    this.selected.set(s);
  }

  onAmountInput(value: string): void {
    this.amount.set(value);
    this.error.set(null);
  }

  /** Parse exact currency units; invalid precision and amounts block dispatch. */
  protected enteredMinor(): number {
    return amountToMinor(this.amount(), this.selected()?.currency ?? 'USD', false);
  }

  cancel(): void {
    if (this.saving()) return;
    this.selected.set(null);
    this.error.set(null);
  }

  close(): void {
    if (this.session.version !== this.sessionVersion) return;
    this.modal.requestClose();
  }

  confirm(): void {
    if (this.session.version !== this.sessionVersion) return;
    const s = this.selected();
    if (!s || this.saving() || this.draftBlocked()) {
      return;
    }
    const cents = this.enteredMinor();
    if (!Number.isSafeInteger(cents) || cents <= 0 || cents > 1_000_000_000_000) {
      this.error.set(
        'Enter an amount greater than zero within the selected currency precision and maximum.',
      );
      return;
    }
    const me = this.dashboard.data()?.viewerParticipantId ?? this.auth.user()?.id ?? '';
    if (
      !me ||
      s.counterparty.id === me ||
      this.dashboard.data()?.ownedParticipantIds?.includes(s.counterparty.id)
    ) {
      this.error.set(
        'These identities belong to the same account. Refresh obligations and review the payment.',
      );
      return;
    }
    // net>0: they owe me → they pay me. net<0: I owe them → I pay them.
    const [payer, payee] = s.netMinor > 0 ? [s.counterparty.id, me] : [me, s.counterparty.id];
    this.dispatch(
      s.groupId,
      {
        payerUserId: payer,
        payeeUserId: payee,
        amountMinor: cents,
        currency: s.currency ?? 'USD',
      },
      s,
    );
  }

  private dispatch(
    groupId: string,
    request: Omit<RecordPaymentRequest, 'operationId'>,
    draft: unknown,
  ): void {
    this.saving.set(true);
    this.modal.setMutationPending(true);
    this.payments
      .recordPayment(groupId, request, draft)
      .pipe(takeUntil(this.session.changes), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.dashboard.refresh();
          this.modal.close();
        },
        error: (err) => {
          this.error.set(
            err instanceof PendingWriteError
              ? err.message
              : (err?.error?.message ?? 'Could not record the payment.'),
          );
          this.retryNotice.set(UNCERTAIN_WRITE);
          this.saving.set(false);
          this.modal.setMutationPending(false);
        },
      });
  }

  retryOriginal(): void {
    if (this.saving() || this.session.version !== this.sessionVersion) return;
    try {
      const pending = this.pendingWrites.read<Omit<RecordPaymentRequest, 'operationId'>>('payment');
      if (!pending) return;
      // Original IDs and amount are immutable, even if aliases were subsequently claimed.
      this.dispatch(pending.scope, pending.request, pending.draft);
    } catch {
      this.error.set('Cannot read the saved retry draft. Enable browser storage and try again.');
    }
  }

  abandonRetry(): void {
    if (this.saving() || this.session.version !== this.sessionVersion) return;
    if (
      !window.confirm(
        'The previous payment may already have been recorded. Check payment history first. Start a separate payment anyway?',
      )
    )
      return;
    try {
      this.pendingWrites.abandon('payment');
      this.retryNotice.set(null);
      this.draftBlocked.set(false);
      this.error.set(null);
    } catch {
      this.error.set(
        'Cannot clear the saved retry identity. Enable browser storage and try again.',
      );
    }
  }
}
