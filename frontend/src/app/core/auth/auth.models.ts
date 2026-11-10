export interface UserSummary {
  id: string;
  email: string;
  displayName: string;
}

export interface AuthResponse {
  sessionId: string;
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: UserSummary;
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface DeviceSession {
  id: string;
  deviceLabel: string;
  createdAt: string;
  refreshedAt: string;
  expiresAt: string;
  current: boolean;
}
