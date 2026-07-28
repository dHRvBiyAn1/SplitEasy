import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../core/api/api.service';
import { GroupType } from '../dashboard/dashboard.models';
import { GroupResponse, GroupSummary, UpdateGroupRequest } from './group.models';

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

  createGroup(name: string, type?: GroupType): Observable<GroupResponse> {
    return this.api.post<GroupResponse>('/groups', { name, type });
  }

  addMember(groupId: string, email: string): Observable<GroupResponse> {
    return this.api.post<GroupResponse>(`/groups/${groupId}/members`, { email });
  }

  /** Partial update from the settings page (rename / type / simplify-debts). */
  updateGroup(groupId: string, patch: UpdateGroupRequest): Observable<GroupResponse> {
    return this.api.patch<GroupResponse>(`/groups/${groupId}`, patch);
  }

  /** Removes a member — pass your own id to leave. Rejected while they owe or are owed. */
  removeMember(groupId: string, memberId: string): Observable<void> {
    return this.api.delete<void>(`/groups/${groupId}/members/${memberId}`);
  }

  /** Creator only; takes the group's expenses and payments with it. */
  deleteGroup(groupId: string): Observable<void> {
    return this.api.delete<void>(`/groups/${groupId}`);
  }
}
