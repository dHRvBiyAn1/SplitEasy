import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../core/api/api.service';
import { UserSummary } from '../core/auth/auth.models';

export interface ExpenseComment {
  id: string;
  expenseId: string;
  author: UserSummary;
  body: string;
  createdAt: string;
}

/** `base` is `/groups/{g}/expenses/{e}` or its `/friends/{f}/expenses/{e}` mirror. */
@Injectable({ providedIn: 'root' })
export class CommentService {
  private readonly api = inject(ApiService);

  list(base: string): Observable<ExpenseComment[]> {
    return this.api.get<ExpenseComment[]>(`${base}/comments`);
  }
  add(base: string, body: string): Observable<ExpenseComment> {
    return this.api.post<ExpenseComment>(`${base}/comments`, { body });
  }
  remove(base: string, id: string): Observable<void> {
    return this.api.delete<void>(`${base}/comments/${id}`);
  }
}
