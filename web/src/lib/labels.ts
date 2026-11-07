import type {
  ClaimStatus,
  ClaimType,
  DocumentReference,
  DocumentType,
  FactState,
  Finding,
  Role,
  StageName,
  WithdrawalReason,
} from "./types";

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
  CLAIM_PROCESSING_STARTED: "Processing started",
  CLAIM_AWAITING_INFORMATION: "Waiting for documents",
  CLAIM_READY_FOR_REVIEW: "Ready for review",
  CLAIM_FACTS_CONSOLIDATED: "Facts combined",
  CLAIM_PROCESSING_RETRY_REQUESTED: "Retry requested",
  CLAIM_INFORMATION_WAIT_EXPIRED: "Wait for documents ended",
  DOCUMENT_SENT_FOR_EXTRACTION: "Document sent for reading",
  DOCUMENT_EXTRACTED: "Document read",
  DOCUMENT_EXTRACTION_FAILED: "Document could not be read",
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

export const STAGE_LABELS: Record<StageName, string> = {
  DOCUMENT_EXTRACTION: "Reading documents",
  CONSOLIDATION: "Combining facts across documents",
  VALIDATION: "Checking dates, totals, and required documents",
};

export const PROCESSING_FAILURE_MESSAGES: Record<string, string> = {
  document_unreadable: "This file could not be opened. Replace it with a clean copy.",
  model_output_invalid: "This document could not be read reliably. Retry, or replace it with a clearer copy.",
  model_unavailable: "The AI model is unavailable right now. Retry in a few minutes.",
  ai_service_unavailable: "The AI service could not be reached. Retry once it is running.",
  route_not_permitted: "No model cleared for claim data is available. Ask an administrator to check the model routes.",
  default: "This step did not finish. Retry, and ask an administrator if it keeps failing.",
};

export const FACT_STATE_LABELS: Record<FactState, string> = {
  PRESENT: "Found",
  MISSING: "Not stated",
  UNCERTAIN: "Uncertain",
  CONFLICTING: "Documents differ",
};

const FIELD_LABELS: Record<string, string> = {
  policy_number: "Policy number",
  claimant_name: "Claimant",
  patient_name: "Patient",
  hospital_name: "Hospital",
  admission_date: "Admission date",
  discharge_date: "Discharge date",
  claimed_amount: "Claimed amount",
  bill_number: "Bill number",
  bill_date: "Bill date",
  total_amount: "Total",
  diagnosis: "Diagnosis",
  procedure: "Procedure",
  treating_doctor: "Doctor",
  document_date: "Document date",
  test_name: "Test",
  findings: "Findings",
  medicines: "Medicines",
};

export function fieldLabel(name: string): string {
  return FIELD_LABELS[name] ?? name;
}

export const UNCERTAINTY_MESSAGES: Record<string, string> = {
  MODEL_UNSURE: "The reader was not sure of this value.",
  NO_SOURCE: "No source could be given for this value.",
  QUOTE_NOT_FOUND: "The quoted text was not found on the page.",
  VALUE_NOT_IN_QUOTE: "The quoted text does not contain this value.",
  UNREADABLE_FORMAT: "This could not be read as a valid date or amount.",
  REREAD_DISAGREES: "A second reading of this scan gave a different value.",
  REREAD_UNAVAILABLE: "This scan could not be read a second time to confirm the value.",
  PAGES_DISAGREE: "Different pages of this document state different values.",
};

function moneyOrText(value: string | null): string {
  const amount = Number(value);
  return value !== null && !Number.isNaN(amount)
    ? new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR" }).format(amount)
    : (value ?? "");
}

export function findingMessage(finding: Finding, documents: DocumentReference[]): string {
  const name = documents.find((document) => document.id === finding.documentId)?.originalFilename ?? "A document";
  const field = fieldLabel(finding.fieldName ?? "");
  const typeLabel = (value: string | null) => DOCUMENT_TYPE_LABELS[value as DocumentType] ?? value ?? "";
  switch (finding.code) {
    case "MISSING_REQUIRED_DOCUMENT":
      return `${typeLabel(finding.expectedValue)} is required and has not been provided.`;
    case "VALUES_CONFLICT":
      return `${field}: documents state different values. None has been chosen.`;
    case "VALUE_UNCERTAIN":
      return `${field} in ${name} could not be read reliably.`;
    case "VALUE_MISSING":
      return `${field} is not stated in ${name}.`;
    case "DISCHARGE_BEFORE_ADMISSION":
      return "The discharge date is before the admission date.";
    case "DIFFERS_FROM_ENTERED_DETAILS":
      return `${field}: entered as ${finding.fieldName === "claimed_amount" ? moneyOrText(finding.expectedValue) : finding.expectedValue}, but the documents state ${finding.fieldName === "claimed_amount" ? moneyOrText(finding.actualValue) : finding.actualValue}.`;
    case "LINE_ITEMS_DO_NOT_MATCH_TOTAL":
      return `${name}: the charges add up to ${moneyOrText(finding.actualValue)}, but the stated total is ${moneyOrText(finding.expectedValue)}.`;
    case "LINE_ITEMS_UNVERIFIED":
      return `${name}: some charges could not be read reliably, so the total was not checked.`;
    case "CLAIMED_AMOUNT_DIFFERS_FROM_BILLS":
      return `The claimed amount is ${moneyOrText(finding.expectedValue)}, but the bills total ${moneyOrText(finding.actualValue)}.`;
    case "DOCUMENT_TYPE_DIFFERS":
      return `${name} was uploaded as ${typeLabel(finding.expectedValue)}, but reads as ${typeLabel(finding.actualValue)}.`;
    default:
      return finding.code;
  }
}
