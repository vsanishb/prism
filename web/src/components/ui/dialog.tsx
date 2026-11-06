"use client";

import { X } from "lucide-react";
import { Dialog as Primitive } from "radix-ui";
import type { ReactNode } from "react";

interface DialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: string;
  children: ReactNode;
}

export function Dialog({ open, onOpenChange, title, description, children }: DialogProps) {
  return (
    <Primitive.Root open={open} onOpenChange={onOpenChange}>
      <Primitive.Portal>
        <Primitive.Overlay className="fixed inset-0 z-40 bg-ink/25" />
        <Primitive.Content
          {...(description ? {} : { "aria-describedby": undefined })}
          className="fixed top-1/2 left-1/2 z-50 max-h-[85vh] w-[calc(100vw-32px)] max-w-md -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-panel bg-surface p-6 shadow-overlay focus:outline-none"
        >
          <div className="mb-5 flex items-start justify-between gap-4">
            <div>
              <Primitive.Title className="text-base font-semibold">{title}</Primitive.Title>
              {description ? (
                <Primitive.Description className="mt-1 text-[13px] text-ink-muted">{description}</Primitive.Description>
              ) : null}
            </div>
            <Primitive.Close aria-label="Close" className="rounded-control p-1 text-ink-muted hover:bg-subtle">
              <X aria-hidden className="size-4" />
            </Primitive.Close>
          </div>
          {children}
        </Primitive.Content>
      </Primitive.Portal>
    </Primitive.Root>
  );
}
