import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../core/api/api.service';
import { SimplifiedDebtsResponse } from './debt.models';

/** Read-only: the minimal set of transfers that settles a group, derived from current balances. */
@Injectable({ providedIn: 'root' })
export class DebtService {
  private readonly api = inject(ApiService);

  getSimplifiedDebts(groupId: string): Observable<SimplifiedDebtsResponse> {
    return this.api.get<SimplifiedDebtsResponse>(`/groups/${groupId}/debt-simplification`);
  }
}
