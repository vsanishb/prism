# Documents

Secure storage, validation, and reading of files. Package: `dev.prism.core.document`.

## Public API

| Type | Purpose |
|---|---|
| `DocumentService` | Store, replace, and remove. **Performs no authorization:** callers check permission and owner state, and call within a transaction |
| `DocumentAccessGuard` | Implemented by owning modules, to decide who may read their documents |
| `DocumentOwner`, `DocumentType`, `DocumentView` | Shared types |

This module knows nothing about claims or policies. Adding a new owner means implementing a guard.

## Upload pipeline

Spool to a temp file → size limit → `ContentInspector` → page limit → SHA-256 and duplicate check → store → insert and audit. If the transaction rolls back, the stored file is deleted. Refusals are audited independently of it.

`ContentInspector` decides type from content only. Images are measured from headers, so oversized images are refused without being decoded.

## Reading

`GET /api/v1/documents/{id}`, `/pages/{n}`, and `/content`. All go through the owner's guard. Page views and downloads are audited, and responses are `no-store`.

`PageRenderer` turns any page into a PNG ([D-051](../decisions.md)). Concurrency and output size are capped.

## Behavior to know

- Documents are never deleted: replaced and removed files stay on record.
- The same file twice on one owner is refused. Across owners it is allowed, because M7 must be able to see it.
- Filenames are display text only, and never build a path.
- Three limits must stay ordered: per-type limits < Spring multipart limit (60 MB) < Next.js proxy limit (62 MB) ([D-054](../decisions.md)).
- Local storage is not encrypted. S3-compatible encrypted storage arrives in M9.

## Testing

`DocumentHandlingTest`, `ContentInspectorTest`, `FilesystemDocumentStoreTest`, and the M2 end-to-end flows.
