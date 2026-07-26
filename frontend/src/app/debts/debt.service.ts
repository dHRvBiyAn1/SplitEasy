import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, of } from 'rxjs';
import { ApiService } from '../core/api/api.service';
import { SimplifiedDebtsResponse } from './debt.models';

/** Read-only: the minimal set of transfers that settles a group, derived from current balances. */
@Injectable({ providedIn: 'root' })
export class DebtService {
  private readonly api = inject(ApiService);

  getSimplifiedDebts(groupId: string): Observable<SimplifiedDebtsResponse> {
    return this.api.get<SimplifiedDebtsResponse>(`/groups/${groupId}/debt-simplification`);
  }

  /**
   * Simplified debts for several groups at once — simplification is only meaningful *within*
   * a group, so the settle-up modal fans out over the user's groups and merges the results.
   * ponytail: one request per group (a handful in practice); batch server-side if that changes.
   */
  getSimplifiedDebtsForGroups(groupIds: string[]): Observable<SimplifiedDebtsResponse[]> {
    if (groupIds.length === 0) {
      return of([]); // forkJoin([]) never emits
    }
    return forkJoin(groupIds.map((id) => this.getSimplifiedDebts(id)));
  }
}
