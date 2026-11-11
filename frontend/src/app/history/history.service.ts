import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL, ApiService } from '../core/api/api.service';
import { HistoryFilter, HistoryPage } from './history.models';

/** Bounded cursor history and its CSV export, scoped to one group. */
@Injectable({ providedIn: 'root' })
export class HistoryService {
  private readonly api = inject(ApiService);
  // ApiService only reads JSON; the CSV needs a blob response, so it uses HttpClient directly.
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  private query(groupId: string, filter: HistoryFilter, cursor?: string | null): string {
    const params = new URLSearchParams({ groupId });
    if (filter.from) params.set('from', filter.from);
    if (filter.to) params.set('to', filter.to);
    if (cursor) params.set('cursor', cursor);
    return params.toString();
  }

  page(groupId: string, filter: HistoryFilter, cursor?: string | null): Observable<HistoryPage> {
    return this.api.get<HistoryPage>(`/groups/history?${this.query(groupId, filter, cursor)}`);
  }

  exportCsv(groupId: string, filter: HistoryFilter): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/groups/history/export?${this.query(groupId, filter)}`, {
      responseType: 'blob',
    });
  }
}
