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

When a module is implemented, add `<module>.md` covering its purpose, entry points, key types, data, failure behavior, and how to test it.
| [workflows.md](workflows.md) | Durable claim processing, outbox, restart behavior |
| [document-intelligence.md](document-intelligence.md) | Model gateway, extraction, grounding, consolidation, evaluation |
| [policy-knowledge.md](policy-knowledge.md) | Policy ingestion, assembly, validation, review, and publication |
