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
  createdAt: string;
}
