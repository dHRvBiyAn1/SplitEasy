import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../core/api/api.service';
import { HistoryPage } from '../history/history.models';

/** Applied filter, already in wire form (amounts in minor units). */
export interface SearchFilter {
  q?: string;
  groupId?: string;
  from?: string;
  to?: string;
  currency?: string;
  minAmount?: number;
  maxAmount?: number;
}

@Injectable({ providedIn: 'root' })
export class SearchService {
  private readonly api = inject(ApiService);

  page(filter: SearchFilter, cursor?: string | null): Observable<HistoryPage> {
    const params = new URLSearchParams();
    // Fixed order keeps the query stable; blank values are simply omitted.
    for (const key of [
      'q',
      'groupId',
      'from',
      'to',
      'currency',
      'minAmount',
      'maxAmount',
    ] as const) {
      const value = filter[key];
      if (value !== undefined && value !== '') params.set(key, String(value));
    }
    if (cursor) params.set('cursor', cursor);
    return this.api.get<HistoryPage>(`/search?${params}`);
  }
}
