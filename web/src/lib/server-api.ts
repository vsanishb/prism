import "server-only";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import type { CurrentUser } from "./types";

export type ServerResult<T> = { ok: true; data: T } | { ok: false; status: number };

/** Server Component access to the core service, forwarding the visitor's own session. */
export async function serverFetch<T>(path: string): Promise<ServerResult<T>> {
  const cookieHeader = (await cookies()).toString();
  let response: Response;
  try {
    response = await fetch(`${process.env.PRISM_CORE_BASE_URL}${path}`, {
      headers: { cookie: cookieHeader },
      cache: "no-store",
    });
  } catch {
    return { ok: false, status: 503 };
  }
  if (response.status === 401) {
    redirect("/sign-in");
  }
  if (!response.ok) {
    return { ok: false, status: response.status };
  }
  return { ok: true, data: (await response.json()) as T };
}

export async function requireCurrentUser(): Promise<CurrentUser | null> {
  const result = await serverFetch<CurrentUser>("/api/v1/auth/me");
  return result.ok ? result.data : null;
}
