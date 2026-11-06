/** Browser-side API access. Requests stay same-origin, so the HttpOnly session cookie is sent. */

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null,
    readonly fields: { field: string; message: string }[],
  ) {
    super(message);
  }
}

const GENERIC_FAILURE = "Something went wrong. Try again.";

function csrfCookie(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match?.[1] ? decodeURIComponent(match[1]) : null;
}

async function csrfToken(): Promise<string> {
  const existing = csrfCookie();
  if (existing) {
    return existing;
  }
  // The token changes at sign-in and sign-out, so it is fetched when no cookie is present.
  await fetch("/api/v1/auth/csrf", { cache: "no-store" });
  return csrfCookie() ?? "";
}

async function toError(response: Response): Promise<ApiError> {
  let body: { detail?: string; code?: string; fields?: { field: string; message: string }[] } = {};
  try {
    body = await response.json();
  } catch {
    // Non-JSON failures, such as a gateway error, fall back to the generic message.
  }
  return new ApiError(body.detail ?? GENERIC_FAILURE, response.status, body.code ?? null, body.fields ?? []);
}

export async function apiRequest<T>(method: string, path: string, body?: unknown): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      cache: "no-store",
      headers: {
        "Content-Type": "application/json",
        "X-XSRF-TOKEN": await csrfToken(),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError("Prism could not be reached. Check your connection and try again.", 0, null, []);
  }
  if (!response.ok) {
    throw await toError(response);
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}

export function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.message : GENERIC_FAILURE;
}
