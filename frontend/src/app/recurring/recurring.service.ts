import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../core/api/api.service';
import { RecurringSchedule, ScheduleRequest } from './recurring.models';

/** Recurring expense schedules and balance reminders, nested under a group. */
@Injectable({ providedIn: 'root' })
export class RecurringService {
  private readonly api = inject(ApiService);

  list(groupId: string): Observable<RecurringSchedule[]> {
    return this.api.get<RecurringSchedule[]>(`/groups/${groupId}/recurring`);
  }

  create(groupId: string, request: ScheduleRequest): Observable<RecurringSchedule> {
    return this.api.post<RecurringSchedule>(`/groups/${groupId}/recurring`, request);
  }

  update(groupId: string, id: string, request: ScheduleRequest): Observable<RecurringSchedule> {
    return this.api.put<RecurringSchedule>(`/groups/${groupId}/recurring/${id}`, request);
  }

  cancel(groupId: string, id: string): Observable<RecurringSchedule> {
    return this.api.post<RecurringSchedule>(`/groups/${groupId}/recurring/${id}/cancel`, {});
  }

  remind(groupId: string, debtorId: string, currency: string): Observable<unknown> {
    return this.api.post(`/groups/${groupId}/reminders`, { debtorId, currency });
  }
}
