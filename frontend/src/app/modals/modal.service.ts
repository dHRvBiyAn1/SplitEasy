import { Injectable, signal } from '@angular/core';
import { Settlement } from '../dashboard/dashboard.models';

export type ModalKind = 'group' | 'expense' | 'settle' | 'member' | null;

/**
 * Which global modal is open (New group / New expense / Settle up) plus its optional
 * launch context. Rendered once by ModalsComponent in the app shell, so any screen —
 * dashboard header, sidebar, group detail — can open one.
 */
@Injectable({ providedIn: 'root' })
export class ModalService {
  readonly active = signal<ModalKind>(null);
  /** Preselected group for the expense modal (e.g. opened from a group's detail page). */
  readonly expenseGroupId = signal<string | null>(null);
  /** Expense being edited (null = creating a new one). */
  readonly editExpenseId = signal<string | null>(null);
  /** Prefill for the settle-up modal (from a dashboard settlement row). */
  readonly settlePrefill = signal<Settlement | null>(null);
  /** Scope the settle-up list to one group (null = all groups). */
  readonly settleGroupId = signal<string | null>(null);
  /** Group the add-member modal targets. */
  readonly addMemberGroupId = signal<string | null>(null);

  openGroup(): void {
    this.active.set('group');
  }

  openExpense(groupId?: string, expenseId?: string): void {
    this.expenseGroupId.set(groupId ?? null);
    this.editExpenseId.set(expenseId ?? null);
    this.active.set('expense');
  }

  openSettle(prefill?: Settlement, groupId?: string): void {
    this.settlePrefill.set(prefill ?? null);
    this.settleGroupId.set(groupId ?? null);
    this.active.set('settle');
  }

  openAddMember(groupId: string): void {
    this.addMemberGroupId.set(groupId);
    this.active.set('member');
  }

  close(): void {
    this.active.set(null);
    this.expenseGroupId.set(null);
    this.editExpenseId.set(null);
    this.settlePrefill.set(null);
    this.settleGroupId.set(null);
    this.addMemberGroupId.set(null);
  }
}
