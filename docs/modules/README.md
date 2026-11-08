# Module Documentation

Describes modules **as built**. For intended design, see [plans](../plans/README.md).

| Module | Covers |
|---|---|
| [Identity](identity.md) | Accounts, sign-in, sessions, roles |
| [Audit](audit.md) | Tamper-evident event record |
| [Service authentication](service-auth.md) | Trust between core and AI |
| [Web foundation](web-foundation.md) | Design system and shell |
| [Claims](claims.md) | Claim lifecycle and intake |
| [Documents](documents.md) | Upload, validation, storage, viewing |
| [Policies](policies.md) | Policy register |
| [Workflows](workflows.md) | Durable claim processing, outbox, restart behavior |
| [Document intelligence](document-intelligence.md) | Model gateway, extraction, grounding, consolidation, evaluation |
| [Policy knowledge](policy-knowledge.md) | Policy ingestion, assembly, validation, review, and publication |
| [Retrieval](retrieval.md) | Search index, hybrid search, and the retrieval explorer |
| [Policy Agent](policy-agent.md) | Issue-by-issue analysis of a claim against its policy, with checked citations and calculations |
| [Human review](review.md) | Tasks, routing, the workspace, information requests, escalation, investigation, outcomes, corrections, and the evidence chain |
| [Risk screening](risk.md) | Rules, Isolation Forest and LOF, scoring, routing, the checked AI summary, and the demonstrations |

When a module is implemented, add `<module>.md` covering its purpose, entry points, key types, data, failure behavior, and how to test it.
