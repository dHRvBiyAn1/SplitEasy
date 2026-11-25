import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../core/api/api.service';
import { AnalyticsFilter, AnalyticsResponse } from './analytics.models';

@Injectable({ providedIn: 'root' })
export class AnalyticsService {
  private readonly api = inject(ApiService);

  read(filter: AnalyticsFilter): Observable<AnalyticsResponse> {
    const params = new URLSearchParams({ groupBy: filter.groupBy });
    if (filter.groupId) params.set('groupId', filter.groupId);
    if (filter.from) params.set('from', filter.from);
    if (filter.to) params.set('to', filter.to);
    return this.api.get<AnalyticsResponse>(`/analytics?${params}`);
  }
}
