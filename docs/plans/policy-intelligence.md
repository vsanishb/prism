# Policy Intelligence

## Purpose
Transform policy documents into validated, structured knowledge that preserves meaning, hierarchy, and traceability.

## Scope
**In:** ingestion, layout-aware extraction, provisions, relationships, validation, human publication.
**Out:** chunking, embedding, and search (M5).

## Design considerations

**Layered records.** Source PDF → raw page extraction → normalized structure → provisions → chunks. Each layer is stored separately, so errors can be traced ([architecture](../architecture.md#knowledge-schema-owner-ai-service)).

**Provision types**

| Type | Must retain |
|---|---|
| Benefit | Name, amount or limit, conditions, qualifications, exceptions |
| Clause | Identifier, subclauses, conditions |
| Waiting period | Condition or treatment, duration, exceptions |
| Exclusion | Scope and its exceptions, always kept distinct from coverage |
| Definition | Term and meaning, independently identifiable |
| Procedure | Notification, reimbursement, and documentation rules |
| Schedule | Sum insured, deductible, copayment, validity period |

**Tables.** Extract as rows with typed cells and header relationships, never flattened text. Each benefit row keeps its limit and conditions together. Merged cells and footnotes are resolved during extraction.

**Cross-page content.** Batches overlap by one page. An assembly step joins continued clauses and tables, and resolves footnotes and cross-references to provision IDs. An unresolved reference is a validation finding.

**Validation**
- Completeness: every page processed, and clause numbering has no unexplained gaps.
- Structure: required attributes per type, and resolvable references.
- Source consistency: critical amounts and durations must appear in the page text layer, or otherwise get an independent second read.
- Logic: sublimits do not exceed the sum insured, and periods are valid.

**Publication.** Critical values require human confirmation ([D-030](../decisions.md)). Published versions are immutable, and corrections create a new version.

## Dependencies
M3 gateway and workflow patterns. Evaluation needs synthetic policies with complex tables.

## Components
`policy_knowledge` pipeline (extract, assemble, normalize, validate); `knowledge` tables; `PolicyIngestionWorkflow`; `policy` publication logic; screens for progress, structure explorer, and side-by-side validation.

## Testing
Fixtures for tables, merged cells, footnotes, multi-page clauses, and exclusions with exceptions; validation rules; publication permissions and immutability; the evaluation set.

## Completion criteria
A synthetic policy publishes with no unresolved critical findings, and every provision opens at its source page.
