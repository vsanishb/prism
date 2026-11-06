import type { ClaimStatus, ClaimType, DocumentType, Role, WithdrawalReason } from "./types";

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
  POLICY_CREATED: "Policy created",
  POLICY_VERSION_CREATED: "Policy version added",
  CLAIM_CREATED: "Claim created",
  CLAIM_UPDATED: "Claim edited",
  CLAIM_SUBMITTED: "Claim submitted",
  CLAIM_WITHDRAWN: "Claim withdrawn",
  DOCUMENT_UPLOADED: "Document uploaded",
  DOCUMENT_REJECTED: "Upload refused",
  DOCUMENT_REPLACED: "Document replaced",
  DOCUMENT_REMOVED: "Document removed",
  DOCUMENT_VIEWED: "Document page viewed",
  DOCUMENT_DOWNLOADED: "Document downloaded",
  AUDIT_CHAIN_VERIFIED: "Audit trail verified",
};

export const CLAIM_STATUS_LABELS: Record<ClaimStatus, string> = {
  DRAFT: "Draft",
  SUBMITTED: "Submitted",
  PROCESSING: "Processing",
  AWAITING_INFORMATION: "Awaiting information",
  IN_REVIEW: "In review",
  ESCALATED: "Escalated",
  DECIDED: "Decided",
  WITHDRAWN: "Withdrawn",
};

export const CLAIM_TYPE_LABELS: Record<ClaimType, string> = {
  REIMBURSEMENT: "Reimbursement",
  CASHLESS: "Cashless",
};

export const DOCUMENT_TYPE_LABELS: Record<DocumentType, string> = {
  CLAIM_FORM: "Claim form",
  HOSPITAL_BILL: "Hospital bill",
  ITEMIZED_INVOICE: "Itemized invoice",
  DISCHARGE_SUMMARY: "Discharge summary",
  PRESCRIPTION: "Prescription",
  DIAGNOSTIC_REPORT: "Diagnostic report",
  POLICY_WORDING: "Policy wording",
  OTHER: "Other",
};

export const WITHDRAWAL_REASON_LABELS: Record<WithdrawalReason, string> = {
  CLAIMANT_REQUEST: "Claimant asked to withdraw",
  DUPLICATE_ENTRY: "Duplicate entry",
  ENTERED_IN_ERROR: "Entered in error",
};

export const AUDIT_ACTIONS = Object.keys(ACTION_LABELS);

export function actionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action;
}
