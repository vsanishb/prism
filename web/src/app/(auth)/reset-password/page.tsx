import type { Metadata } from "next";
import { SetPasswordForm } from "@/features/auth/set-password-form";

export const metadata: Metadata = { title: "Reset password" };

export default function ResetPasswordPage() {
  return <SetPasswordForm expectedPurpose="PASSWORD_RESET" />;
}
