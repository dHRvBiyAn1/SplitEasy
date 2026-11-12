import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../core/api/api.service';
import { ActivityPage } from './activity.models';

@Injectable({ providedIn: 'root' })
export class ActivityService {
  private readonly api = inject(ApiService);

  page(groupId?: string | null, cursor?: string | null): Observable<ActivityPage> {
    const params = new URLSearchParams();
    if (groupId) params.set('groupId', groupId);
    if (cursor) params.set('cursor', cursor);
    const query = params.toString();
    return this.api.get<ActivityPage>(`/activity${query ? `?${query}` : ''}`);
  }
}
