import type {
  AnomalyDetector,
  IndicatorKind,
  ReviewRoute,
  RiskBand,
  RiskCategory,
  RiskSeverity,
  ClaimStatus,
  ClaimType,
  DocumentReference,
  DocumentType,
  FactState,
  FindingStatus,
  IssueKind,
  Finding,
  KnowledgeStatus,
  PolicyFinding,
  PolicyProvision,
  PolicyRelation,
  PolicySearchResult,
  PolicyValueKind,
  ProvisionType,
  Role,
  SearchIndexStatus,
  StageName,
  ValueRole,
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
  POLICY_PAGES_EXTRACTED: "Policy pages read",
  POLICY_KNOWLEDGE_READY_FOR_REVIEW: "Policy ready for review",
  POLICY_VALUE_CONFIRMED: "Policy value confirmed",
  POLICY_VALUE_CORRECTED: "Policy value corrected",
  POLICY_VERSION_PUBLISHED: "Policy version published",
  POLICY_VERSION_REJECTED: "Policy version rejected",
  POLICY_INGESTION_RETRY_REQUESTED: "Policy reading retried",
  POLICY_SEARCH_INDEX_BUILT: "Policy search index built",
  POLICY_SEARCH_INDEX_RETRY_REQUESTED: "Policy indexing retried",
  CLAIM_SENT_FOR_POLICY_ANALYSIS: "Claim sent for policy analysis",
  CLAIM_POLICY_ANALYZED: "Claim policy analyzed",
  CLAIM_RISK_SCREENED: "Claim screened for risk",
  CLAIM_SENT_FOR_RISK_SUMMARY: "Claim risk signals sent for an AI summary",
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
  POLICY_ANALYSIS: "Reading the policy against the facts",
  RISK_SCREENING: "Screening for risk signals",
};

export const PROCESSING_FAILURE_MESSAGES: Record<string, string> = {
  document_unreadable: "This file could not be opened. Replace it with a clean copy.",
  model_output_invalid: "This document could not be read reliably. Retry, or replace it with a clearer copy.",
  model_unavailable: "The AI model is unavailable right now. Retry in a few minutes.",
  ai_service_unavailable: "The AI service could not be reached. Retry once it is running.",
  route_not_permitted: "No model cleared for claim data is available. Ask an administrator to check the model routes.",
  policy_not_published: "The policy version for this claim is not published yet. Publish it, then retry.",
  policy_not_searchable: "The policy version for this claim is not indexed for search yet. Retry once it is searchable.",
  index_not_found: "The policy version for this claim is not indexed for search yet. Retry once it is searchable.",
  anomaly_result_mismatch: "Anomaly detection returned a result that does not fit this claim. Retry, and ask an administrator if it keeps failing.",
  no_consolidated_facts: "There are no combined facts to work from yet. Retry once the documents are read.",
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

export const KNOWLEDGE_STATUS_LABELS: Record<KnowledgeStatus, string> = {
  NO_WORDING: "No wording",
  PROCESSING: "Reading",
  IN_REVIEW: "In review",
  PUBLISHED: "Published",
  REJECTED: "Rejected",
};

export const PROVISION_TYPE_LABELS: Record<ProvisionType, string> = {
  SCHEDULE: "Schedule",
  DEFINITION: "Definitions",
  BENEFIT: "Benefits",
  WAITING_PERIOD: "Waiting periods",
  EXCLUSION: "Exclusions",
  PROCEDURE: "Claim procedure",
  CLAUSE: "Other clauses",
};

export const RELATION_LABELS: Record<PolicyRelation["relation"], string> = {
  QUALIFIES: "Conditions in",
  LIMITS: "Limited by",
  EXCEPTS: "Exception in",
  DEFINES: "Uses a term defined in",
  REFERENCES: "Refers to",
};

/** For a related provision that cites the result: read as "<label> <result>". */
export const INCOMING_RELATION_LABELS: Record<PolicyRelation["relation"], string> = {
  QUALIFIES: "Sets conditions on",
  LIMITS: "Limits",
  EXCEPTS: "Makes an exception to",
  DEFINES: "Defines a term used in",
  REFERENCES: "Referred to by",
};

export const SEARCH_INDEX_LABELS: Record<SearchIndexStatus, string> = {
  NOT_PUBLISHED: "Not searchable until published",
  QUEUED: "Waiting to be indexed",
  INDEXING: "Indexing for search",
  READY: "Searchable",
  FAILED: "Indexing failed",
};

export const DEGRADED_LABELS: Record<PolicySearchResult["degraded"][number], string> = {
  SEMANTIC_UNAVAILABLE: "Meaning-based search was unavailable, so results come from word matching only.",
  RERANK_UNAVAILABLE: "Reranking was unavailable, so results are in the combined order of the first stages.",
};

const DURATION_UNITS: Record<string, [string, string]> = {
  HOURS: ["hour", "hours"],
  DAYS: ["day", "days"],
  MONTHS: ["month", "months"],
  YEARS: ["year", "years"],
};

/** A policy value for reading: money in rupees, percentages, and durations with their unit. */
export function policyValueText(kind: PolicyValueKind, value: string, unit: string | null, currency: string): string {
  switch (kind) {
    case "AMOUNT":
      return new Intl.NumberFormat("en-IN", { style: "currency", currency }).format(Number(value));
    case "PERCENTAGE":
      return `${Number(value)}%`;
    case "DURATION": {
      const [one, many] = DURATION_UNITS[unit ?? ""] ?? ["", ""];
      return `${value} ${Number(value) === 1 ? one : many}`.trim();
    }
    default:
      return value;
  }
}

export const POLICY_VALUE_PROBLEMS: Record<string, string> = {
  NO_SOURCE: "No source could be given for this value.",
  QUOTE_NOT_FOUND: "The quoted text was not found on the page.",
  VALUE_NOT_IN_QUOTE: "The quoted text does not contain this value.",
  UNREADABLE_FORMAT: "This could not be read as a number.",
  SCANNED_PAGE: "Scanned page: check the value against the page.",
};

export function policyFindingMessage(finding: PolicyFinding, provisions: PolicyProvision[], currency: string): string {
  const provision = provisions.find((item) => item.id === finding.provisionId);
  const where = provision ? `Clause ${provision.key ?? provision.title}` : "The wording";
  const money = (value: string | null) =>
    value === null ? "" : new Intl.NumberFormat("en-IN", { style: "currency", currency }).format(Number(value));
  switch (finding.code) {
    case "PAGES_NOT_READ":
      return `Pages ${finding.actualValue?.replace(/[[\]]/g, "")} were not read.`;
    case "SUM_INSURED_DIFFERS_FROM_REGISTER":
      return `The schedule states a sum insured of ${money(finding.actualValue)}, but the register has ${money(finding.expectedValue)}.`;
    case "LIMIT_EXCEEDS_SUM_INSURED":
      return `${where} sets a limit of ${money(finding.actualValue)}, above the sum insured of ${money(finding.expectedValue)}.`;
    case "PERCENTAGE_OVER_100":
      return `${where} states a percentage above 100.`;
    case "VALUE_UNCERTAIN":
      return `${where}: a value could not be confirmed against the page.`;
    case "PROVISION_TEXT_UNVERIFIED":
      return `${where}: its text does not fully match the page. Compare it with the source.`;
    case "EXCEPTION_NOT_CAPTURED":
      return `${where} mentions an exception that was not captured separately.`;
    case "WAITING_PERIOD_WITHOUT_DURATION":
      return `${where} is a waiting period with no duration found.`;
    case "BENEFIT_WITHOUT_LIMIT":
      return `${where} is a benefit with no limit found.`;
    case "UNRESOLVED_REFERENCE":
      return `${where} cites Clause ${finding.expectedValue}, which was not found.`;
    case "REFERENCE_NOT_IN_TEXT":
      return `${where} was linked to Clause ${finding.expectedValue}, but its text does not cite it.`;
    case "NUMBERING_GAP":
      return `Clause ${finding.expectedValue} is missing from the numbering. Check whether it was skipped.`;
    default:
      return finding.code;
  }
}

/** Statuses describe what the wording says about the facts. None is a claim decision. */
export const FINDING_STATUS_LABELS: Record<FindingStatus, string> = {
  SUPPORTED: "Supported by the wording",
  LIMITED: "Limited by the wording",
  POTENTIALLY_EXCLUDED: "Possibly excluded",
  INSUFFICIENT_EVIDENCE: "Not enough evidence",
  NEEDS_HUMAN_INTERPRETATION: "Needs interpretation",
};

export const ISSUE_KIND_LABELS: Record<IssueKind, string> = {
  COVERAGE: "Cover",
  LIMIT: "Limit",
  WAITING_PERIOD: "Waiting period",
  EXCLUSION: "Exclusion",
  COST_SHARING: "Cost sharing",
  CONDITION: "Condition",
};

export const VALUE_ROLE_LABELS: Record<ValueRole, string> = {
  WAITING_PERIOD: "Waiting period",
  AMOUNT_LIMIT: "Amount limit",
  DAILY_LIMIT: "Daily limit",
  PERCENT_OF_SUM_INSURED: "Share of sum insured",
  COPAYMENT: "Co-payment",
  DEDUCTIBLE: "Deductible",
};

/** Why Prism changed or set aside part of a finding. Shown so a reviewer can see what was checked. */
export const FINDING_PROBLEM_LABELS: Record<string, string> = {
  CITATION_UNKNOWN: "A cited passage was not among those retrieved.",
  CITATION_QUOTE_MISMATCH: "A quote did not match the wording it cited.",
  NO_VALID_CITATION: "No passage of the wording was cited.",
  FACT_UNKNOWN: "A fact named was not one of the claim's facts.",
  FACT_NOT_ESTABLISHED: "It relied on a fact that is not established.",
  VALUE_REJECTED: "A policy value it named did not belong to a cited provision.",
  SEARCH_LIMIT_REACHED: "The search allowance ran out first.",
  BUDGET_EXHAUSTED: "The analysis allowance ran out first.",
  NOT_IN_THIS_CLAIM_OR_VERSION: "Part of it did not match this claim or its policy version.",
};

export const FACT_NAME_LABELS: Record<string, string> = {
  admission_date: "Admission date",
  discharge_date: "Discharge date",
  claimed_amount: "Claimed amount",
  diagnosis: "Diagnosis",
  procedure: "Procedure",
  test_name: "Test",
  findings: "Test findings",
  medicines: "Medicines",
};

// --- Risk screening (M7). The words matter: these are signals for review, never findings of fraud. ---

export const RISK_BAND_LABELS: Record<RiskBand, string> = {
  LOW: "Low risk",
  MEDIUM: "Medium risk",
  HIGH: "High risk",
};

export const REVIEW_ROUTE_LABELS: Record<ReviewRoute, string> = {
  STANDARD_REVIEW: "Standard review",
  SENIOR_REVIEW: "Senior review or investigation",
};

export const RISK_CATEGORY_LABELS: Record<RiskCategory, string> = {
  CONSISTENCY: "Consistency",
  FINANCIAL: "Financial",
  DUPLICATE: "Duplicates and other claims",
  DOCUMENT: "Documents",
  MISSING_INFORMATION: "Missing information",
  STATISTICAL: "Statistical anomalies",
};

export const INDICATOR_KIND_LABELS: Record<IndicatorKind, string> = {
  DATA_INCONSISTENCY: "Data inconsistency",
  RISK_INDICATOR: "Risk indicator",
  FRAUD_INDICATOR: "Fraud indicator",
};

export const INDICATOR_KIND_DESCRIPTIONS: Record<IndicatorKind, string> = {
  DATA_INCONSISTENCY: "Documents, or documents and the entered details, disagree.",
  RISK_INDICATOR: "A pattern that needs a closer look.",
  FRAUD_INDICATOR: "A pattern that may warrant investigation. It is not a finding of fraud.",
};

export const RISK_SEVERITY_LABELS: Record<RiskSeverity, string> = {
  LOW: "Low",
  MEDIUM: "Medium",
  HIGH: "High",
};

export const DETECTOR_LABELS: Record<AnomalyDetector, { name: string; question: string; method: string }> = {
  ISOLATION_FOREST: {
    name: "Isolation Forest",
    question: "Is this claim unusual compared with all baseline claims?",
    method: "Isolates claims with random splits. Unusual claims are isolated in fewer splits.",
  },
  LOCAL_OUTLIER_FACTOR: {
    name: "Local Outlier Factor",
    question: "Is this claim unusual compared with similar claims?",
    method: "Compares how densely packed this claim's nearest baseline claims are with how densely packed theirs are.",
  },
};

export const RISK_FEATURE_LABELS: Record<string, string> = {
  claimedAmount: "Claimed amount",
  sumInsuredRatio: "Share of the sum insured",
  lengthOfStayDays: "Length of stay (nights)",
  amountPerDay: "Amount per day of stay",
  lineItemCount: "Bill line items",
  largestItemShare: "Largest line item's share of the bills",
  billDeviation: "Claimed amount compared with the bills",
  priorClaims12m: "Patient's earlier claims in the past year",
  daysSinceCoverStart: "Days from cover start to admission",
};

export const RISK_SUMMARY_PROBLEMS: Record<string, string> = {
  model_output_invalid: "The summary the model wrote did not pass Prism's checks, so it is not shown.",
  model_unavailable: "The AI model was unavailable.",
  ai_service_unavailable: "The AI service could not be reached.",
  summary_rejected: "The summary did not pass Prism's checks, so it is not shown.",
  default: "No summary could be written.",
};
