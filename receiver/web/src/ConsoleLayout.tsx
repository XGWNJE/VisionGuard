import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardAction,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Empty, EmptyHeader, EmptyTitle } from "@/components/ui/empty";
import { Field, FieldLabel } from "@/components/ui/field";
import { useEffect, useRef, useState, type ReactNode } from "react";

export function SectionCard({
  title,
  children,
  action,
  className = "",
}: {
  title: string;
  children: ReactNode;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <Card className={`section-card ${className}`}>
      <CardHeader className="section-header">
        <CardTitle role="heading" aria-level={2}>
          {title}
        </CardTitle>
        {action && <CardAction>{action}</CardAction>}
      </CardHeader>
      <CardContent className="section-body">{children}</CardContent>
    </Card>
  );
}

export function FormRow({
  label,
  id,
  children,
  action,
}: {
  label: string;
  id?: string;
  children: ReactNode;
  action?: ReactNode;
}) {
  return (
    <Field orientation="horizontal" className="form-row">
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <div className="form-control">{children}</div>
      <div className="field-action">{action}</div>
    </Field>
  );
}

export function LongPage({
  sections,
}: {
  sections: { id: string; label: string; content: ReactNode }[];
}) {
  const root = useRef<HTMLDivElement>(null);
  const [current, setCurrent] = useState(sections[0]?.id ?? "");
  const ids = sections.map((s) => s.id).join("|");
  useEffect(() => {
    const scroll = root.current?.closest(".console-scroll");
    if (!scroll) return;
    const update = () => {
      const top = scroll.getBoundingClientRect().top + 36;
      const entries = [
        ...(root.current?.querySelectorAll<HTMLElement>("[data-section]") ??
          []),
      ];
      const active =
        entries.filter((e) => e.getBoundingClientRect().top <= top).at(-1) ??
        entries[0];
      if (active) setCurrent(active.id);
    };
    update();
    scroll.addEventListener("scroll", update, { passive: true });
    return () => scroll.removeEventListener("scroll", update);
  }, [ids]);
  return (
    <div className="long-page" ref={root}>
      <nav className="page-anchors" aria-label="页内定位">
        {sections.map((section) => (
          <Button
            key={section.id}
            asChild
            variant={current === section.id ? "secondary" : "ghost"}
          >
            <a
              href={`#${section.id}`}
              aria-current={current === section.id ? "location" : undefined}
              onClick={(event) => {
                event.preventDefault();
                root.current
                  ?.querySelector<HTMLElement>(`#${section.id}`)
                  ?.scrollIntoView({
                    block: "start",
                    behavior: matchMedia("(prefers-reduced-motion: reduce)")
                      .matches
                      ? "instant"
                      : "smooth",
                  });
              }}
            >
              {section.label}
            </a>
          </Button>
        ))}
      </nav>
      <div className="long-content">
        {sections.map((section) => (
          <section
            key={section.id}
            id={section.id}
            data-section
            className="long-section"
          >
            {section.content}
          </section>
        ))}
      </div>
    </div>
  );
}

export function EmptyState({ text }: { text: string }) {
  return (
    <Empty>
      <EmptyHeader>
        <EmptyTitle>{text}</EmptyTitle>
      </EmptyHeader>
    </Empty>
  );
}

export function ConfirmDialog({
  title,
  description,
  confirmText,
  onClose,
  onConfirm,
}: {
  title: string;
  description: string;
  confirmText: string;
  onClose: () => void;
  onConfirm: () => void;
}) {
  return (
    <AlertDialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>取消</AlertDialogCancel>
          <AlertDialogAction asChild>
            <Button variant="destructive" onClick={onConfirm}>
              {confirmText}
            </Button>
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
