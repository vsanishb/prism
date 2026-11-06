import { NextResponse, type NextRequest } from "next/server";

const PUBLIC_PATHS = ["/sign-in", "/accept-invite", "/reset-password"];
const SESSION_COOKIES = ["prism_session", "__Host-prism_session"];

function contentSecurityPolicy(nonce: string): string {
  // Next.js development tooling needs eval. Production builds do not get it.
  const devOnly = process.env.NODE_ENV === "development" ? " 'unsafe-eval'" : "";
  return [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${devOnly}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self'",
    "connect-src 'self'" + (devOnly ? " ws:" : ""),
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join("; ");
}

/**
 * Sets security headers and sends visitors without a session to sign-in. This redirect is a
 * convenience only: every authorization decision is made by the core service.
 */
export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const hasSession = SESSION_COOKIES.some((name) => request.cookies.has(name));
  if (!hasSession && !PUBLIC_PATHS.includes(pathname)) {
    return NextResponse.redirect(new URL("/sign-in", request.url));
  }

  const nonce = Buffer.from(crypto.randomUUID()).toString("base64");
  const policy = contentSecurityPolicy(nonce);
  const headers = new Headers(request.headers);
  headers.set("x-nonce", nonce);
  headers.set("Content-Security-Policy", policy);

  const response = NextResponse.next({ request: { headers } });
  response.headers.set("Content-Security-Policy", policy);
  response.headers.set("X-Content-Type-Options", "nosniff");
  response.headers.set("X-Frame-Options", "DENY");
  response.headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
  return response;
}

export const config = {
  matcher: ["/((?!api|_next/static|_next/image|favicon.ico).*)"],
};
