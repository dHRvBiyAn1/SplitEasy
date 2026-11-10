import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../core/api/api.service';
import { PersonBalance } from '../dashboard/dashboard.models';
import { Currency } from '../shared/currency';
import { formatMoney } from '../shared/money.component';
import { DirectObligation, GroupBalancesResponse } from './balance.models';

@Injectable({ providedIn: 'root' })
export class BalanceService {
  private readonly api = inject(ApiService);

  getBalances(groupId: string): Observable<GroupBalancesResponse> {
    return this.api.get<GroupBalancesResponse>(`/groups/${groupId}/balances`);
  }

  getObligations(groupId: string): Observable<DirectObligation[]> {
    return this.api.get<DirectObligation[]>(`/groups/${groupId}/balances/obligations`);
  }

  /** Canonical debts for the selected group mode, including departure blockers. */
  getCurrentObligations(groupId: string): Observable<DirectObligation[]> {
    return this.api.get<DirectObligation[]>(`/groups/${groupId}/balances/current-obligations`);
  }

  /** Pairwise "who owes whom, to me" for the group (positive = they owe you). */
  getMyBalances(groupId: string): Observable<PersonBalance[]> {
    return this.api.get<PersonBalance[]>(`/groups/${groupId}/balances/mine`);
  }
}

/** A human sentence for a member's net position, e.g. "is owed $12.50" / "owes $8.00" / "is settled up". */
export function describeBalance(netMinor: number, currency: Currency = 'USD'): string {
  if (netMinor === 0) {
    return 'is settled up';
  }
  const amount = formatMoney(Math.abs(netMinor), false, currency);
  return netMinor > 0 ? `is owed ${amount}` : `owes ${amount}`;
}
