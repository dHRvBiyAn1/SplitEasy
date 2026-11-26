import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../core/api/api.service';
import { SplitRule, SplitRuleRequest } from './split-rule.models';

/** The caller's private saved split rules for one group; applying one never posts anything. */
@Injectable({ providedIn: 'root' })
export class SplitRuleService {
  private readonly api = inject(ApiService);

  list(groupId: string): Observable<SplitRule[]> {
    return this.api.get<SplitRule[]>(`/groups/${groupId}/split-rules`);
  }

  create(groupId: string, request: SplitRuleRequest): Observable<SplitRule> {
    return this.api.post<SplitRule>(`/groups/${groupId}/split-rules`, request);
  }
}
