# Web Foundation

The design system and application shell. Code: `web/src`. Design: [plan](../plans/frontend-experience.md).

## Layout

| Path | Contents |
|---|---|
| `app/` | Thin routes. `(auth)` for public pages, `(app)` for the signed-in shell |
| `features/` | Feature components |
| `components/ui/` | Shared primitives |
| `lib/` | API access, labels, formatting, types |
| `proxy.ts` | Security headers, CSP nonce, sign-in redirect |

Design tokens live in `app/globals.css`. Use them, never raw colors.

Sizes are in rem from a 17px root, so the interface scales as a whole. Use the type scale (`text-xs`, `text-sm`, `text-base`, and up), not pixel sizes. The sidebar groups navigation into Work and Administration; its labels are stable, because the browser tests find links by name.

## Home page

`/overview` shows four figures, recent claims, risk bands, review work by queue, and outcomes, from `GET /api/v1/overview` ([D-097](../decisions.md)), then links and roles. Staff without claim access see only the links and roles.

## Data access

- Server Components use `serverFetch`, which forwards the visitor's cookies to core.
- Client components use `apiRequest`, which adds the CSRF header. Mutations then call `router.refresh()`.
- Browser requests to `/api/*` are rewritten to core, so everything is same-origin.

## Behavior to know

- Hiding navigation is cosmetic. Core enforces all access, and pages handle `403` by showing `AccessDenied`.
- The CSRF cookie is cleared at sign-in and sign-out, and `apiRequest` fetches a new one when needed.
- CSP needs a per-request nonce, so all pages render dynamically.
- Dates display in Indian Standard Time through `lib/format.ts`.

## Deferred from the plan

API types are written by hand in `lib/types.ts`. TanStack Query and form libraries are not installed ([D-049](../decisions.md)), and pdf.js was replaced by server-side rendering ([D-051](../decisions.md)).

## Testing

Vitest for utilities, Playwright in `e2e/` against the real stack.
