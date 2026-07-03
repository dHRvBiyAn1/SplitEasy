import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../core/api/api.service';
import { GroupResponse, GroupSummary } from './group.models';

/** One service per backend resource (AGENTS.md); all calls go through ApiService. */
@Injectable({ providedIn: 'root' })
export class GroupService {
  private readonly api = inject(ApiService);

  listMyGroups(): Observable<GroupSummary[]> {
    return this.api.get<GroupSummary[]>('/groups');
  }

  getGroup(id: string): Observable<GroupResponse> {
    return this.api.get<GroupResponse>(`/groups/${id}`);
  }

  createGroup(name: string): Observable<GroupResponse> {
    return this.api.post<GroupResponse>('/groups', { name });
  }

  addMember(groupId: string, email: string): Observable<GroupResponse> {
    return this.api.post<GroupResponse>(`/groups/${groupId}/members`, { email });
  }
}
