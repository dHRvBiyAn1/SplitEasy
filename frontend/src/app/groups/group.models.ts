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
  /** Group-wide: settle-up suggests the fewest payments for this group. */
  simplifyDebts: boolean;
  createdAt: string;
}

/** Partial update from the settings page — omitted fields are left untouched. */
export interface UpdateGroupRequest {
  name?: string;
  type?: GroupType;
  simplifyDebts?: boolean;
}
