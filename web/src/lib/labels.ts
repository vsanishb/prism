import type { Role } from "./types";

export const ROLE_LABELS: Record<Role, string> = {
  ADMIN: "Administrator",
  POLICY_MANAGER: "Policy manager",
  CLAIMS_PROCESSOR: "Claims processor",
  REVIEWER: "Reviewer",
  SENIOR_REVIEWER: "Senior reviewer",
  FRAUD_INVESTIGATOR: "Fraud investigator",
  AUDITOR: "Auditor",
};

export const ROLE_DESCRIPTIONS: Record<Role, string> = {
  ADMIN: "Manages staff accounts and monitors the platform. Does not decide claims.",
  POLICY_MANAGER: "Uploads, validates, and publishes policy versions.",
  CLAIMS_PROCESSOR: "Creates claims and manages their documents.",
  REVIEWER: "Reviews claim evidence and records outcomes.",
  SENIOR_REVIEWER: "Handles escalated and high-value claims.",
  FRAUD_INVESTIGATOR: "Investigates risk indicators and records findings.",
  AUDITOR: "Reads the audit trail and verifies its integrity.",
};

const ACTION_LABELS: Record<string, string> = {
  SIGN_IN: "Sign-in",
  SIGN_OUT: "Sign-out",
  ACCESS_DENIED: "Access denied",
  INVITATION_CREATED: "Invitation created",
  INVITATION_REVOKED: "Invitation revoked",
  INVITATION_ACCEPTED: "Invitation accepted",
  PASSWORD_RESET_ISSUED: "Password reset issued",
  PASSWORD_RESET_COMPLETED: "Password reset completed",
  USER_ROLES_CHANGED: "Roles changed",
  USER_DEACTIVATED: "User deactivated",
  USER_REACTIVATED: "User reactivated",
  SYNTHETIC_USER_SEEDED: "Synthetic user created",
  AUDIT_CHAIN_VERIFIED: "Audit trail verified",
};

export const AUDIT_ACTIONS = Object.keys(ACTION_LABELS);

export function actionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action;
}
