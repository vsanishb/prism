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
export type Permission =
  | "USERS_MANAGE"
  | "AUDIT_READ"
  | "SYSTEM_VIEW"
  | "POLICY_READ"
  | "POLICY_MANAGE"
  | "CLAIM_READ"
  | "CLAIM_MANAGE";

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

export const CLAIM_STATUSES = [
  "DRAFT",
  "SUBMITTED",
  "PROCESSING",
  "AWAITING_INFORMATION",
  "IN_REVIEW",
  "ESCALATED",
  "DECIDED",
  "WITHDRAWN",
] as const;

export type ClaimStatus = (typeof CLAIM_STATUSES)[number];
export type ClaimType = "REIMBURSEMENT" | "CASHLESS";
export type WithdrawalReason = "CLAIMANT_REQUEST" | "DUPLICATE_ENTRY" | "ENTERED_IN_ERROR";
export type ClaimAction = "EDIT" | "SUBMIT" | "WITHDRAW" | "ADD_DOCUMENT" | "REMOVE_DOCUMENT";

export const CLAIM_DOCUMENT_TYPES = [
  "CLAIM_FORM",
  "HOSPITAL_BILL",
  "ITEMIZED_INVOICE",
  "DISCHARGE_SUMMARY",
  "PRESCRIPTION",
  "DIAGNOSTIC_REPORT",
  "OTHER",
] as const;

export type DocumentType = (typeof CLAIM_DOCUMENT_TYPES)[number] | "POLICY_WORDING";

export interface DocumentRecord {
  id: string;
  documentType: DocumentType;
  status: "ACTIVE" | "SUPERSEDED" | "REMOVED";
  originalFilename: string;
  mediaType: string;
  sizeBytes: number;
  pageCount: number;
  supersedesId: string | null;
  uploadedAt: string;
}

export interface PolicySummary {
  id: string;
  policyNumber: string;
  productName: string;
  policyholderName: string;
  createdAt: string;
}

export type KnowledgeStatus = "NO_WORDING" | "PROCESSING" | "IN_REVIEW" | "PUBLISHED" | "REJECTED";

export interface PolicyVersion {
  id: string;
  versionNumber: number;
  effectiveFrom: string;
  effectiveTo: string;
  sumInsured: string;
  currency: string;
  knowledgeStatus: KnowledgeStatus;
}

export interface PolicyDetail {
  policy: PolicySummary;
  versions: { version: PolicyVersion; documents: DocumentRecord[] }[];
}

export interface ClaimSummary {
  id: string;
  claimNumber: string;
  status: ClaimStatus;
  type: ClaimType;
  patientName: string;
  hospitalName: string;
  admissionDate: string;
  claimedAmount: string;
  currency: string;
  createdAt: string;
}

export interface ClaimDetail {
  summary: ClaimSummary;
  claimantName: string;
  dischargeDate: string;
  policy: { id: string; policyNumber: string; productName: string; policyholderName: string };
  policyVersion: PolicyVersion | null;
  policyVersionFixed: boolean;
  withdrawalReason: WithdrawalReason | null;
  submittedAt: string | null;
  version: number;
  documents: DocumentRecord[];
  submissionIssues: string[];
  availableActions: ClaimAction[];
}

export interface TimelineEntry {
  occurredAt: string;
  action: string;
  outcome: AuditOutcome;
  actorName: string | null;
}

export type StageName = "DOCUMENT_EXTRACTION" | "CONSOLIDATION" | "VALIDATION";
export type StageStatus = "NOT_STARTED" | "PROCESSING" | "COMPLETED" | "REQUIRES_REVIEW" | "FAILED";

export interface StageView {
  stage: StageName;
  documentId: string | null;
  status: StageStatus;
  detailCode: string | null;
  occurredAt: string;
}

export interface ProcessingView {
  claimStatus: ClaimStatus;
  retryAvailable: boolean;
  stages: StageView[];
}

export type FactState = "PRESENT" | "MISSING" | "UNCERTAIN" | "CONFLICTING";
export type FactKind = "TEXT" | "DATE" | "AMOUNT";
export type Grounding = "TEXT_MATCH" | "PAGE_ONLY" | "NONE";

export interface FactSource {
  documentId: string;
  state: "PRESENT" | "UNCERTAIN";
  confidence: "HIGH" | "MEDIUM" | "LOW";
  rawValue: string | null;
  value: string | null;
  page: number | null;
  quote: string | null;
  region: string[] | number[] | null;
  grounding: Grounding;
  reason: string | null;
}

export interface ClaimFact {
  id: string;
  name: string;
  kind: FactKind;
  critical: boolean;
  documentId: string | null;
  state: FactState;
  value: string | null;
  sources: FactSource[];
}

export interface LineItem {
  documentId: string;
  position: number;
  description: string;
  quantity: string | null;
  unitPrice: string | null;
  amount: string | null;
  rawAmount: string;
  state: "PRESENT" | "UNCERTAIN";
  page: number;
  quote: string;
  region: string[] | number[] | null;
  grounding: Grounding;
}

export interface Finding {
  code: string;
  severity: "ERROR" | "WARNING" | "INFO";
  fieldName: string | null;
  documentId: string | null;
  expectedValue: string | null;
  actualValue: string | null;
}

export interface DocumentReference {
  id: string;
  originalFilename: string;
  documentType: DocumentType;
  classifiedType: DocumentType | null;
  pageCount: number;
}

export interface ClaimFacts {
  sequence: number | null;
  consolidatedAt: string | null;
  complete: boolean;
  facts: ClaimFact[];
  lineItems: LineItem[];
  findings: Finding[];
  documents: DocumentReference[];
}

export type ProvisionType = "SCHEDULE" | "DEFINITION" | "BENEFIT" | "WAITING_PERIOD" | "EXCLUSION" | "PROCEDURE" | "CLAUSE";
export type PolicyValueKind = "AMOUNT" | "PERCENTAGE" | "DURATION" | "TEXT";

export interface ValueReview {
  decision: "CONFIRMED" | "CORRECTED";
  correctedValue: string | null;
  note: string | null;
  reviewerName: string;
  reviewedAt: string;
}

export interface PolicyValue {
  id: string;
  name: string;
  kind: PolicyValueKind;
  rawValue: string;
  value: string | null;
  unit: string | null;
  qualifier: string | null;
  state: "PRESENT" | "UNCERTAIN";
  page: number | null;
  quote: string | null;
  region: string[] | number[] | null;
  grounding: Grounding;
  problem: string | null;
  critical: boolean;
  review: ValueReview | null;
}

export interface PolicyProvision {
  id: string;
  key: string | null;
  type: ProvisionType;
  title: string;
  text: string;
  pageStart: number;
  pageEnd: number;
  quote: string;
  region: string[] | number[] | null;
  grounding: Grounding;
  textVerified: boolean;
  parentKey: string | null;
  exceptions: string[];
  values: PolicyValue[];
}

export interface PolicyRelation {
  fromProvisionId: string;
  targetKey: string;
  toProvisionId: string | null;
  relation: "QUALIFIES" | "LIMITS" | "EXCEPTS" | "DEFINES" | "REFERENCES";
  origin: "MODEL" | "RULE";
  statedInText: boolean;
}

export interface PolicyFinding {
  code: string;
  severity: "ERROR" | "WARNING" | "INFO";
  provisionId: string | null;
  valueId: string | null;
  expectedValue: string | null;
  actualValue: string | null;
}

export interface PolicyKnowledge {
  versionId: string;
  versionNumber: number;
  knowledgeStatus: KnowledgeStatus;
  sumInsured: string;
  currency: string;
  documentId: string | null;
  documentName: string | null;
  pageCount: number | null;
  uploadedByName: string | null;
  batches: { firstPage: number; lastPage: number; status: "PROCESSING" | "COMPLETED" | "FAILED"; detailCode: string | null }[];
  provisions: PolicyProvision[];
  relations: PolicyRelation[];
  findings: PolicyFinding[];
  blockers: { code: string; message: string }[];
  publishedByName: string | null;
  publishedAt: string | null;
  rejectionReason: string | null;
}
