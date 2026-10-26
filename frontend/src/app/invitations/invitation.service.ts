import { Injectable, inject } from '@angular/core';
import { ApiService } from '../core/api/api.service';
import {
  InvitationStatus,
  EmailInvitationRequest,
  InvitationPreview,
  InvitationAccepted,
} from './invitation.models';

/** Invitation delivery/status is independent from account access and guest creation. */
@Injectable({ providedIn: 'root' })
export class InvitationService {
  private readonly api = inject(ApiService);
  preview(token: string) {
    return this.api.post<InvitationPreview>('/invitations/preview', { token });
  }
  accept(token: string, confirmOwnership: boolean) {
    return this.api.post<InvitationAccepted>('/invitations/accept', {
      token,
      ...(confirmOwnership ? { confirmOwnership: true } : {}),
    });
  }
  list(groupId: string) {
    return this.api.get<InvitationStatus[]>(`/groups/${groupId}/invitations`);
  }
  link(groupId: string) {
    return this.api.post<{ invitation: InvitationStatus; url: string }>(
      `/groups/${groupId}/invitations/links`,
      {},
    );
  }
  revoke(groupId: string, id: string) {
    return this.api.delete<void>(`/groups/${groupId}/invitations/${id}`);
  }
  email(groupId: string, request: EmailInvitationRequest) {
    return this.api.post<InvitationStatus>(`/groups/${groupId}/invitations/email`, request);
  }
}
