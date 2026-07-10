import { Component, OnInit, effect, inject, input, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '../core/auth/auth.service';
import { centsToDisplay, dollarsToCents } from '../expenses/expense.service';
import { GroupResponse } from '../groups/group.models';
import { PaymentResponse, SettlePrefill } from './payment.models';
import { PaymentService } from './payment.service';

@Component({
  selector: 'app-settle-up-panel',
  imports: [ReactiveFormsModule],
  templateUrl: './settle-up-panel.component.html',
  styleUrl: './settle-up-panel.component.scss',
})
export class SettleUpPanelComponent implements OnInit {
  readonly group = input.required<GroupResponse>();
  /** Set by the parent when a balance row is clicked; prefills the form. */
  readonly prefill = input<SettlePrefill | null>(null);
  /** Emitted after a payment is recorded, so the parent refreshes balances. */
  readonly paymentRecorded = output<void>();

  private readonly fb = inject(FormBuilder);
  private readonly payments = inject(PaymentService);
  private readonly auth = inject(AuthService);

  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly history = signal<PaymentResponse[]>([]);
  /** True once the form is valid and the user asked to record — shows the confirm step. */
  protected readonly confirming = signal(false);

  protected readonly form = this.fb.nonNullable.group({
    payerUserId: ['', [Validators.required]],
    payeeUserId: ['', [Validators.required]],
    amount: [null as number | null, [Validators.required, Validators.min(0.01)]],
  });

  protected readonly display = centsToDisplay;

  protected readonly nameOf = (id: string) =>
    this.group().members.find((m) => m.id === id)?.displayName ?? '';

  constructor() {
    // React to a balance-row click: pay toward reducing the imbalance.
    effect(() => {
      const p = this.prefill();
      if (!p) {
        return;
      }
      if (p.kind === 'transaction') {
        // Explicit suggested transfer (debt simplification): seed payer/payee/amount directly.
        this.form.setValue({
          payerUserId: p.payerUserId,
          payeeUserId: p.payeeUserId,
          amount: p.amountCents / 100,
        });
        this.confirming.set(false);
        return;
      }
      // kind === 'balance': infer direction relative to the current user.
      const me = this.auth.user()?.id ?? '';
      if (!me || p.userId === me) {
        // Can't settle with yourself from a row; just focus the form as-is.
        return;
      }
      // Negative net = they owe → they pay me. Positive = they're owed → I pay them.
      const [payer, payee] = p.netCents < 0 ? [p.userId, me] : [me, p.userId];
      this.form.setValue({
        payerUserId: payer,
        payeeUserId: payee,
        amount: Math.abs(p.netCents) / 100,
      });
      this.confirming.set(false);
    });
  }

  ngOnInit(): void {
    this.form.patchValue({ payerUserId: this.auth.user()?.id ?? '' });
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.payments.listPayments(this.group().id).subscribe({
      next: (rows) => {
        this.history.set(rows);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load payments.');
        this.loading.set(false);
      },
    });
  }

  amountCentsPreview(): number {
    return dollarsToCents(this.form.getRawValue().amount ?? 0);
  }

  /** Plain method (not a computed): reactive-form state isn't a signal, so this must
   *  re-evaluate each change-detection cycle. Called directly from the template. */
  protected formValid(): boolean {
    const v = this.form.getRawValue();
    return this.form.valid && v.payerUserId !== v.payeeUserId;
  }

  review(): void {
    if (!this.formValid()) {
      return;
    }
    this.error.set(null);
    this.confirming.set(true);
  }

  cancel(): void {
    this.confirming.set(false);
  }

  confirm(): void {
    if (!this.formValid() || this.saving()) {
      return;
    }
    this.saving.set(true);
    const v = this.form.getRawValue();
    this.payments
      .recordPayment(this.group().id, {
        payerUserId: v.payerUserId,
        payeeUserId: v.payeeUserId,
        amountCents: dollarsToCents(v.amount ?? 0),
      })
      .subscribe({
        next: (saved) => {
          this.history.update((rows) => [saved, ...rows]);
          this.form.reset({ payerUserId: this.auth.user()?.id ?? '', payeeUserId: '', amount: null });
          this.confirming.set(false);
          this.saving.set(false);
          this.paymentRecorded.emit();
        },
        error: (err) => {
          this.error.set(err?.error?.message ?? 'Could not record the payment.');
          this.confirming.set(false);
          this.saving.set(false);
        },
      });
  }

  /** Up to two initials for a payer avatar, e.g. "Ada Lovelace" → "AL". */
  initials(name: string): string {
    return name
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]?.toUpperCase() ?? '')
      .join('');
  }
}
