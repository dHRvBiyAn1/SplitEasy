import { Injectable, signal } from '@angular/core';
import { Settlement } from '../dashboard/dashboard.models';

export type ModalKind = 'group' | 'expense' | 'settle' | null;

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
  /** Prefill for the settle-up modal (from a dashboard settlement row). */
  readonly settlePrefill = signal<Settlement | null>(null);

  openGroup(): void {
    this.active.set('group');
  }

  openExpense(groupId?: string): void {
    this.expenseGroupId.set(groupId ?? null);
    this.active.set('expense');
  }

  openSettle(prefill?: Settlement): void {
    this.settlePrefill.set(prefill ?? null);
    this.active.set('settle');
  }

  close(): void {
    this.active.set(null);
    this.expenseGroupId.set(null);
    this.settlePrefill.set(null);
  }
}
