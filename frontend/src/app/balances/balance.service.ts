import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../core/api/api.service';
import { GroupBalancesResponse } from './balance.models';

@Injectable({ providedIn: 'root' })
export class BalanceService {
  private readonly api = inject(ApiService);

  getBalances(groupId: string): Observable<GroupBalancesResponse> {
    return this.api.get<GroupBalancesResponse>(`/groups/${groupId}/balances`);
  }
}

/** A human sentence for a member's net position, e.g. "is owed $12.50" / "owes $8.00" / "is settled up". */
export function describeBalance(netCents: number): string {
  if (netCents === 0) {
    return 'is settled up';
  }
  const abs = Math.abs(netCents);
  const amount = `$${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
  return netCents > 0 ? `is owed ${amount}` : `owes ${amount}`;
}
