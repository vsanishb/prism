import { SearchX } from "lucide-react";
import { EmptyState } from "@/components/ui/feedback";
import { Panel } from "@/components/ui/layout";

export default function NotFound() {
  return (
    <Panel>
      <EmptyState icon={SearchX} title="This could not be found">
        It may not exist, or you may not have access to it.
      </EmptyState>
    </Panel>
  );
}
