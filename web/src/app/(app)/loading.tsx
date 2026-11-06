import { Skeleton } from "@/components/ui/feedback";

export default function Loading() {
  return (
    <div className="space-y-6" aria-label="Loading">
      <Skeleton className="h-8 w-56" />
      <Skeleton className="h-64" />
    </div>
  );
}
