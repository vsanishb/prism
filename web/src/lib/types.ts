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

export type StageName = "DOCUMENT_EXTRACTION" | "CONSOLIDATION" | "VALIDATION" | "POLICY_ANALYSIS" | "RISK_SCREENING";
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

export type SearchIndexStatus = "NOT_PUBLISHED" | "QUEUED" | "INDEXING" | "READY" | "FAILED";

export interface SearchIndex {
  status: SearchIndexStatus;
  chunkCount: number | null;
  embeddingModel: string | null;
  failureCode: string | null;
  requestedAt: string | null;
  completedAt: string | null;
}

export interface StageScore {
  rank: number;
  score: number;
}

export interface RelatedLink {
  provisionId: string;
  relation: PolicyRelation["relation"];
  direction: "OUTGOING" | "INCOMING";
}

export interface SearchResultItem {
  provisionId: string;
  key: string | null;
  type: ProvisionType;
  title: string;
  sectionPath: string[];
  pageStart: number;
  pageEnd: number;
  text: string;
  part: number;
  parts: number;
  finalRank: number;
  pinned: boolean;
  exact: "CLAUSE" | "TERM" | null;
  semantic: StageScore | null;
  lexical: StageScore | null;
  fused: StageScore;
  rerank: StageScore | null;
  quote: string | null;
  region: string[] | number[] | null;
  related: RelatedLink[];
}

export interface StageHit {
  provisionId: string;
  key: string | null;
  type: ProvisionType;
  title: string;
  rank: number;
  score: number;
}

export interface RelatedProvision {
  provisionId: string;
  key: string | null;
  type: ProvisionType;
  title: string;
  pageStart: number;
  pageEnd: number;
  text: string;
  quote: string | null;
  region: string[] | number[] | null;
}

export interface PolicySearchResult {
  versionId: string;
  documentId: string;
  query: string;
  degraded: ("SEMANTIC_UNAVAILABLE" | "RERANK_UNAVAILABLE")[];
  embeddingModel: string;
  rerankModel: string | null;
  results: SearchResultItem[];
  semantic: StageHit[];
  lexical: StageHit[];
  related: RelatedProvision[];
}

export type IssueKind = "COVERAGE" | "LIMIT" | "WAITING_PERIOD" | "EXCLUSION" | "COST_SHARING" | "CONDITION";
export type FindingStatus = "SUPPORTED" | "LIMITED" | "POTENTIALLY_EXCLUDED" | "INSUFFICIENT_EVIDENCE" | "NEEDS_HUMAN_INTERPRETATION";
export type ValueRole = "WAITING_PERIOD" | "AMOUNT_LIMIT" | "DAILY_LIMIT" | "PERCENT_OF_SUM_INSURED" | "COPAYMENT" | "DEDUCTIBLE";

export interface AssessmentCitation {
  provisionId: string;
  key: string | null;
  title: string;
  type: ProvisionType | null;
  pageStart: number;
  quote: string;
  region: string[] | number[] | null;
}

export interface AssessmentCalculation {
  role: ValueRole;
  valueName: string | null;
  inputs: [string, string][];
  steps: string[];
  resultAmount: string | null;
  resultDate: string | null;
  outcome: string;
}

export interface AssessmentFinding {
  position: number;
  kind: IssueKind;
  question: string;
  status: FindingStatus;
  proposedStatus: FindingStatus | null;
  reasoning: string;
  unresolved: string[];
  problems: string[];
  rounds: number;
  queries: string[];
  facts: { id: string; name: string; state: FactState; value: string | null }[];
  citations: AssessmentCitation[];
  calculations: AssessmentCalculation[];
}

export interface PolicyAssessment {
  id: string;
  versionId: string;
  wordingDocumentId: string | null;
  coverStart: string;
  createdAt: string;
  current: boolean;
  degradedSearches: number;
  pipelineVersion: string;
  findings: AssessmentFinding[];
}

// --- Risk screening (M7). Signals for review: never a finding of fraud. ---

export type RiskBand = "LOW" | "MEDIUM" | "HIGH";
export type ReviewRoute = "STANDARD_REVIEW" | "SENIOR_REVIEW";
export type RiskCategory = "CONSISTENCY" | "FINANCIAL" | "DUPLICATE" | "DOCUMENT" | "MISSING_INFORMATION" | "STATISTICAL";
export type IndicatorKind = "DATA_INCONSISTENCY" | "RISK_INDICATOR" | "FRAUD_INDICATOR";
export type RiskSeverity = "LOW" | "MEDIUM" | "HIGH";
export type AnomalyDetector = "ISOLATION_FOREST" | "LOCAL_OUTLIER_FACTOR";

export interface RiskEvidence {
  label: string;
  detail: string | null;
  documentId: string | null;
  page: number | null;
  region: number[] | null;
  factId: string | null;
  lineItemId: string | null;
  matchedClaim: { id: string; claimNumber: string } | null;
}

export interface RiskIndicator {
  position: number;
  signalId: string | null;
  ruleId: string;
  ruleVersion: number;
  category: Exclude<RiskCategory, "STATISTICAL">;
  kind: IndicatorKind;
  severity: RiskSeverity;
  points: number;
  title: string;
  explanation: string;
  evidence: RiskEvidence[];
}

export interface RiskDetectorResult {
  detector: AnomalyDetector;
  signalId: string | null;
  score: number;
  threshold: number;
  percentile: number;
  flagged: boolean;
  points: number;
}

export interface RiskFeature {
  name: string;
  value: number;
  imputed: boolean;
  baselineMedian: number;
  baselinePercentile: number;
}

export interface RiskRule {
  id: string;
  version: number;
  category: Exclude<RiskCategory, "STATISTICAL">;
  kind: IndicatorKind;
  severity: RiskSeverity;
  title: string;
  checks: string;
  triggered: boolean;
}

export interface RiskSummary {
  status: "AVAILABLE" | "UNAVAILABLE" | "NOT_NEEDED";
  text: string | null;
  cited: string[];
  signals: Record<string, string>;
  problem: string | null;
  promptVersion: string | null;
}

export interface RiskScreening {
  id: string;
  createdAt: string;
  current: boolean;
  score: number;
  band: RiskBand;
  route: ReviewRoute;
  rulesOnlyScore: number;
  rulesOnlyBand: RiskBand;
  thresholds: { mediumFrom: number; highFrom: number; maximum: number };
  versions: { ruleset: string; scoring: string; features: string; model: string; fingerprint: string; baselineSize: number };
  categories: { category: RiskCategory; points: number; cap: number; contribution: number }[];
  indicators: RiskIndicator[];
  detectors: RiskDetectorResult[];
  features: RiskFeature[];
  rules: RiskRule[];
  summary: RiskSummary | null;
}

export type DemonstrationScenarioKey = "SEVERE" | "CLEAN" | "DISGUISED";

export interface DemonstrationClaim {
  id: string;
  claimNumber: string;
  status: ClaimStatus;
}

export interface DemonstrationScenario {
  scenario: DemonstrationScenarioKey;
  claim: DemonstrationClaim;
  earlierClaims: DemonstrationClaim[];
  ready: boolean;
}
