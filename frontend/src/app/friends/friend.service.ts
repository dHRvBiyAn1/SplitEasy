import { Injectable, inject } from '@angular/core';
import { ApiService } from '../core/api/api.service';
import { FriendDetail, FriendSummary } from './friend.models';
@Injectable({ providedIn: 'root' })
export class FriendService {
  private readonly api = inject(ApiService);
  remove(id: string) {
    return this.api.delete<void>(`/friends/${id}`);
  }
  save(groupId: string, participantId: string) {
    return this.api.post<FriendSummary>('/friends', { groupId, participantId });
  }
  get(id: string) {
    return this.api.get<FriendDetail>(`/friends/${id}`);
  }
  list() {
    return this.api.get<FriendSummary[]>('/friends');
  }
}
