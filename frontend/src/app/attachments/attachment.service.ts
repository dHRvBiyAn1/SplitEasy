import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL, ApiService } from '../core/api/api.service';
import { UserSummary } from '../core/auth/auth.models';

export interface ExpenseAttachment {
  id: string;
  expenseId: string;
  uploadedBy: UserSummary;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  createdAt: string;
}

/** `base` is `/groups/{g}/expenses/{e}` or its `/friends/{f}/expenses/{e}` mirror. */
@Injectable({ providedIn: 'root' })
export class AttachmentService {
  private readonly api = inject(ApiService);
  // ApiService only reads JSON; the file bytes need a blob response.
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  list(base: string): Observable<ExpenseAttachment[]> {
    return this.api.get<ExpenseAttachment[]>(`${base}/attachments`);
  }
  upload(base: string, file: File): Observable<ExpenseAttachment> {
    const form = new FormData();
    form.append('file', file);
    return this.api.post<ExpenseAttachment>(`${base}/attachments`, form);
  }
  download(base: string, id: string): Observable<Blob> {
    return this.http.get(`${this.baseUrl}${base}/attachments/${id}`, { responseType: 'blob' });
  }
  remove(base: string, id: string): Observable<void> {
    return this.api.delete<void>(`${base}/attachments/${id}`);
  }
}
