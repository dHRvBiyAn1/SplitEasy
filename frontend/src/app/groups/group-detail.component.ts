import { Component, OnInit, computed, effect, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { UserSummary } from '../core/auth/auth.models';
import { BalanceService } from '../balances/balance.service';
import { PersonBalance, ExpenseCategory } from '../dashboard/dashboard.models';
import { DashboardService } from '../dashboard/dashboard.service';
import { centsToDisplay } from '../expenses/expense.service';
import { ExpenseService } from '../expenses/expense.service';
import { ExpenseSummary } from '../expenses/expense.models';
import { ModalService } from '../modals/modal.service';
import { GroupService } from './group.service';
import { GroupResponse } from './group.models';

const CATEGORY_GLYPH: Record<ExpenseCategory, string> = {
  FOOD_DRINK: '☕',
  GROCERIES: '🧺',
  RENT_HOME: '⌂',
  UTILITIES: '⚡',
  TRAVEL: '✈',
  TRANSPORT: '🚕',
  FUN: '✦',
  OTHER: '◆',
};

interface MonthGroup {
  label: string;
  items: ExpenseSummary[];
}

@Component({
  selector: 'app-group-detail',
  imports: [RouterLink],
  templateUrl: './group-detail.component.html',
  styleUrl: './group-detail.component.scss',
})
export class GroupDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly groups = inject(GroupService);
  private readonly balances = inject(BalanceService);
  private readonly expensesApi = inject(ExpenseService);
  private readonly auth = inject(AuthService);
  private readonly dashboard = inject(DashboardService);
  protected readonly modal = inject(ModalService);

  protected readonly display = centsToDisplay;

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly group = signal<GroupResponse | null>(null);
  protected readonly myBalances = signal<PersonBalance[]>([]);
  protected readonly expenses = signal<ExpenseSummary[]>([]);

  private groupId = '';

  /** My net in this group = sum of the pairwise nets (positive = I'm owed overall). */
  protected readonly myNet = computed(() => this.myBalances().reduce((s, b) => s + b.netCents, 0));

  /** Expenses grouped by "MONTH YEAR", newest month first (list already sorted desc). */
  protected readonly months = computed<MonthGroup[]>(() => {
    const out: MonthGroup[] = [];
    for (const e of this.expenses()) {
      const label = this.monthLabel(e.spentOn);
      const last = out[out.length - 1];
      if (last && last.label === label) {
        last.items.push(e);
      } else {
        out.push({ label, items: [e] });
      }
    }
    return out;
  });

  constructor() {
    // Reload group-scoped data whenever the shared dashboard payload changes
    // (a modal action calls dashboard.refresh(), so this stays in sync).
    effect(() => {
      this.dashboard.data();
      if (this.groupId) {
        this.reload();
      }
    });
  }

  ngOnInit(): void {
    this.groupId = this.route.snapshot.paramMap.get('id') ?? '';
    this.reload();
  }

  private reload(): void {
    this.groups.getGroup(this.groupId).subscribe({
      next: (g) => {
        this.group.set(g);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.status === 403 ? 'You are not a member of this group.' : 'Could not load the group.');
        this.loading.set(false);
      },
    });
    this.balances.getMyBalances(this.groupId).subscribe({ next: (b) => this.myBalances.set(b) });
    this.expensesApi.listExpenses(this.groupId).subscribe({ next: (e) => this.expenses.set(e) });
  }

  addExpense(): void {
    this.modal.openExpense(this.groupId);
  }

  settleUp(): void {
    this.modal.openSettle();
  }

  meId(): string {
    return this.auth.user()?.id ?? '';
  }

  firstName(u: UserSummary): string {
    return u.id === this.meId() ? 'You' : u.displayName.split(/\s+/)[0];
  }

  memberNames(g: GroupResponse): string {
    return g.members.map((m) => this.firstName(m)).join(', ');
  }

  typeLabel(g: GroupResponse): string {
    const t = (g as unknown as { type?: string }).type ?? 'OTHER';
    return t.charAt(0) + t.slice(1).toLowerCase();
  }

  glyphHue(name: string): number {
    let h = 0;
    for (let i = 0; i < name.length; i++) {
      h = (h * 31 + name.charCodeAt(i)) % 360;
    }
    return h;
  }

  initials(name: string): string {
    return name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]?.toUpperCase() ?? '').join('');
  }

  pillLabel(b: PersonBalance): string {
    const name = b.user.displayName.split(/\s+/)[0];
    return b.netCents > 0 ? `${name} owes you` : `you owe ${name}`;
  }

  categoryGlyph(c: ExpenseCategory): string {
    return CATEGORY_GLYPH[c] ?? '◆';
  }

  paidSub(e: ExpenseSummary): string {
    const who = e.paidBy.id === this.meId() ? 'You' : e.paidBy.displayName.split(/\s+/)[0];
    return `${who} paid $${this.display(e.amountCents)}`;
  }

  deltaLabel(e: ExpenseSummary): string {
    if (e.viewerDeltaCents > 0) return 'you lent';
    if (e.viewerDeltaCents < 0) return 'you borrowed';
    return 'not involved';
  }

  private monthLabel(iso: string): string {
    return new Date(iso + 'T00:00:00').toLocaleDateString('en-US', { month: 'long', year: 'numeric' }).toUpperCase();
  }

  dayNum(iso: string): string {
    return String(new Date(iso + 'T00:00:00').getDate());
  }

  dayMon(iso: string): string {
    return new Date(iso + 'T00:00:00').toLocaleDateString('en-US', { month: 'short' }).toUpperCase();
  }
}
