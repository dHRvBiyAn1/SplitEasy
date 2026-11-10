import { ParticipantSummary } from './participant.models';
import { UserSummary } from '../core/auth/auth.models';
import { GroupType } from '../dashboard/dashboard.models';

export interface GroupSummary {
  id: string;
  name: string;
  type: GroupType;
  memberCount: number;
}

export interface GroupResponse {
  id: string;
  name: string;
  type: GroupType;
  createdBy: UserSummary;
  members: UserSummary[];
  participants?: ParticipantSummary[];
  inactiveGuests?: ParticipantSummary[];
  viewerParticipantId?: string;
  ownedParticipantIds?: string[];
  /** Group-wide: settle-up uses suggested netted transfers for this group. */
  simplifyDebts: boolean;
  createdAt: string;
}

/** Partial update from the settings page — omitted fields are left untouched. */
export interface UpdateGroupRequest {
  name?: string;
  type?: GroupType;
  simplifyDebts?: boolean;
}

/** Explicit pre-G01 account-only adapter; absence of the roster declares the old schema.
 * Never infer ownership by comparing UUIDs in a participant-aware payload. */
export function financialRoster(group: GroupResponse): ParticipantSummary[] {
  if (!group.participants)
    return group.members.map((m) => ({ ...m, accountId: m.id, canonicalParticipantId: m.id }));
  const people = new Map<string, ParticipantSummary>();
  for (const participant of group.participants) {
    const canonical = participant.canonicalParticipantId ?? participant.id;
    if (!people.has(canonical) || participant.id === canonical) people.set(canonical, participant);
  }
  return [...people.values()];
}
export function viewerIdentity(group: GroupResponse, accountId: string): string {
  return group.participants ? (group.viewerParticipantId ?? '') : accountId;
}
export function ownedIdentities(group: GroupResponse, accountId: string): string[] {
  return group.participants ? (group.ownedParticipantIds ?? []) : [accountId];
}
