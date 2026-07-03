import { UserSummary } from '../core/auth/auth.models';

export interface GroupSummary {
  id: string;
  name: string;
  memberCount: number;
}

export interface GroupResponse {
  id: string;
  name: string;
  createdBy: UserSummary;
  members: UserSummary[];
  createdAt: string;
}
