# Policies

The policy register. Package: `dev.prism.core.policy`. Policy knowledge arrives in M4.

## Public API

`PolicyDirectory`: lookups for other modules, including `versionCovering(policyId, date)`.

## Endpoints

Under `/api/v1/policies`. Reads need `POLICY_READ`, and writes need `POLICY_MANAGE`.

| Route | Purpose |
|---|---|
| `GET /`, `GET /{id}`, `POST /` | List, detail, register |
| `POST /{id}/versions` | Add a version |
| `POST /{id}/versions/{versionId}/wording` | Upload the wording, as a PDF |

## Behavior to know

- Periods cannot overlap. An exclusion constraint enforces this in the database, so that concurrent requests cannot get past it.
- A version has one active wording: uploading again supersedes it.
- Version numbers are assigned under a row lock on the policy.
- Versions have no published state yet. Publication arrives in M4, and M6 will require it.

## Testing

`PolicyRegisterTest`, and the policy flow in the M2 end-to-end suite.
