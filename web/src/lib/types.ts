export const ROLES = [
  "ADMIN",
  "POLICY_MANAGER",
  "CLAIMS_PROCESSOR",
  "REVIEWER",
  "SENIOR_REVIEWER",
  "FRAUD_INVESTIGATOR",
  "AUDITOR",
] as const;

export type Role = (typeof ROLES)[number];
export type Permission = "USERS_MANAGE" | "AUDIT_READ" | "SYSTEM_VIEW";

export interface CurrentUser {
  id: string;
  email: string;
  fullName: string;
  roles: Role[];
  permissions: Permission[];
}

export interface User {
  id: string;
  email: string;
  fullName: string;
  status: "ACTIVE" | "DEACTIVATED";
  roles: Role[];
  createdAt: string;
  lastSignInAt: string | null;
}

export interface PendingInvitation {
  id: string;
  email: string;
  fullName: string;
  roles: Role[];
  createdAt: string;
  expiresAt: string;
}

export interface IssuedLink {
  id: string;
  link: string;
  expiresAt: string;
}

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

export type AuditOutcome = "SUCCESS" | "DENIED" | "FAILURE";

export interface AuditEvent {
  id: string;
  stream: string;
  sequence: number;
  occurredAt: string;
  actorId: string | null;
  actorName: string | null;
  actorRoles: Role[];
  action: string;
  targetType: string | null;
  targetId: string | null;
  outcome: AuditOutcome;
  correlationId: string | null;
  details: Record<string, string>;
}

export interface ChainVerification {
  valid: boolean;
  streamsChecked: number;
  eventsChecked: number;
  firstBreak: { stream: string; sequence: number } | null;
}

export interface SystemCheck {
  id: string;
  name: string;
  healthy: boolean;
  detail: string;
}

export interface SystemStatus {
  checkedAt: string;
  checks: SystemCheck[];
}

export interface TokenDetails {
  purpose: "INVITATION" | "PASSWORD_RESET";
  email: string;
  fullName: string | null;
}
