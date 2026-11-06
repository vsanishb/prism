import { CircleAlert, CircleCheck } from "lucide-react";
import { Panel, SectionTitle } from "@/components/ui/layout";

export function SubmissionChecklist({ issues }: { issues: string[] }) {
  return (
    <div>
      <SectionTitle>Before submitting</SectionTitle>
      <Panel className="p-4">
        {issues.length === 0 ? (
          <p className="flex gap-2 text-[13px] text-positive">
            <CircleCheck aria-hidden className="mt-0.5 size-4 shrink-0" />
            Ready to submit.
          </p>
        ) : (
          <ul className="space-y-2">
            {issues.map((issue) => (
              <li key={issue} className="flex gap-2 text-[13px] text-caution">
                <CircleAlert aria-hidden className="mt-0.5 size-4 shrink-0" />
                {issue}
              </li>
            ))}
          </ul>
        )}
      </Panel>
    </div>
  );
}
