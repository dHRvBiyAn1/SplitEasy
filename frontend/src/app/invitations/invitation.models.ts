import { ParticipantSummary } from '../groups/participant.models';
import { Currency } from '../shared/currency';
export interface InvitationStatus {
  id: string;
  kind: 'EMAIL' | 'LINK';
  participantId: string | null;
  participantName: string | null;
  status: string;
  createdAt: string;
  expiresAt: string;
  acceptedAt: string | null;
}
export interface EmailInvitationRequest {
  email: string;
  participantId?: string;
  operationId: string;
  resend?: boolean;
}

export interface InvitationPreview {
  invitationId: string;
  kind: 'EMAIL' | 'LINK';
  groupId: string;
  groupName: string;
  expiresAt: string;
  participant: ParticipantSummary | null;
  requiresOwnershipConfirmation: boolean;
  balances: {
    currency: Currency;
    existingNetMinor: number;
    participantNetMinor: number;
    resultingNetMinor: number;
  }[];
}
export interface InvitationAccepted {
  groupId: string;
  participantId: string;
  accountId: string;
  claimed: boolean;
}

/** Invitation timestamps display in the browser's local time zone. */
export function invitationDate(value: string | null | undefined): string {
  const date = new Date(value ?? '');
  return Number.isFinite(date.getTime())
    ? date.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
    : 'Unavailable';
}
