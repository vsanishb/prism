import type { Metadata } from "next";
import { SetPasswordForm } from "@/features/auth/set-password-form";

export const metadata: Metadata = { title: "Accept invitation" };

export default function AcceptInvitePage() {
  return <SetPasswordForm expectedPurpose="INVITATION" />;
}
