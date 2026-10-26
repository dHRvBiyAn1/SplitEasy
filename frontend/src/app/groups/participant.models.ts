/** Financial identity. Optional ownership metadata is absent only in archived/legacy account-only payloads. */
export interface ParticipantSummary {
  id: string;
  displayName: string;
  email: string | null;
  canonicalParticipantId?: string;
  accountId?: string | null;
}
