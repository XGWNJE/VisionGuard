import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
  NativeSelect,
  NativeSelectOption,
} from "@/components/ui/native-select";
import { Pencil } from "lucide-react";
import React, { useEffect, useRef, useState } from "react";
import { useNavigationDraft } from "./DraftNavigation";
import { NumericSelection } from "./NumericSelection";
import type { Ack, Device, Source } from "./protocol";
type Entry = {
  draft: string;
  editing: boolean;
  request?: string;
  status?: "pending" | "confirmed" | "failed" | "uncertain";
  message?: string;
  model?: string;
};
export type ParameterDrafts = Map<string, Record<string, Entry>>;
export function Parameters({
  node,
  source,
  disabled,
  send,
  acks,
  drafts,
  embedded = false,
}: {
  node: Device;
  source?: Source;
  disabled: boolean;
  send: (m: Record<string, unknown>) => string;
  acks: Ack[];
  drafts: ParameterDrafts;
  embedded?: boolean;
}) {
  const context = `${node.deviceId}/${source?.sourceId ?? ""}`;
  const [entries, setEntries] = useState(() => drafts.get(context) ?? {});
  const config = source ?? node;
  const fields =
    node.nodeType === "sensor"
      ? [
          ["confidence", "置信度阈值"],
          ["cooldown", "报警冷却（秒）"],
        ]
      : [
          ["modelKey", "推理模型"],
          ["confidence", "置信度阈值"],
          ["cooldown", "报警冷却（秒）"],
          ["targetSamplingRate", "采样频率（FPS）"],
          ["targets", "检测目标"],
        ];
  function write(key: string, entry: Entry | null) {
    const next = { ...(drafts.get(context) ?? {}) };
    if (entry) next[key] = entry;
    else delete next[key];
    drafts.set(context, next);
    setEntries(next);
  }
  const summary =
    node.nodeType === "sensor"
      ? `${config.confidence === undefined ? "阈值未报告" : `${Math.round(config.confidence * 100)}%`} · ${config.cooldown === undefined ? "冷却未报告" : `${config.cooldown} 秒`}`
      : `${config.modelKey || "模型未报告"} · ${config.targetSamplingRate === undefined ? "采样未报告" : `${config.targetSamplingRate} FPS`}`;
  const content = (
    <>
      {disabled && (
        <p role="status" className="subtle">
          只读
        </p>
      )}
      <div className="parameter-grid">
        {fields.map(([key, label]) => (
          <ParameterField
            key={key}
            name={key}
            label={label}
            value={config[key as keyof typeof config]}
            model={config.modelKey ?? ""}
            node={node}
            source={source}
            disabled={disabled}
            send={send}
            acks={acks}
            entry={entries[key]}
            write={(entry) => write(key, entry)}
          />
        ))}
      </div>
    </>
  );
  return embedded ? (
    content
  ) : (
    <Card className="parameter-panel">
      <CardHeader>
        <CardTitle role="heading" aria-level={2}>
          检测规则
        </CardTitle>
        <small>{summary}</small>
      </CardHeader>
      <CardContent>{content}</CardContent>
    </Card>
  );
}
function ParameterField({
  name,
  label,
  value,
  model,
  node,
  source,
  disabled,
  send,
  acks,
  entry,
  write,
}: {
  name: string;
  label: string;
  value: unknown;
  model: string;
  node: Device;
  source?: Source;
  disabled: boolean;
  send: (m: Record<string, unknown>) => string;
  acks: Ack[];
  entry?: Entry;
  write: (entry: Entry | null) => void;
}) {
  const id = React.useId(),
    current = value === undefined ? "" : String(value);
  const editButton = useRef<HTMLButtonElement>(null),
    wasEditing = useRef(false);
  useEffect(() => {
    if (wasEditing.current && !entry?.editing) editButton.current?.focus();
    wasEditing.current = !!entry?.editing;
  }, [entry?.editing]);
  const labels = node.modelLabels?.[model] ?? [];
  const [search, setSearch] = useState("");
  const choices = labels.filter((item) =>
    `${item.label} ${item.value}`
      .toLowerCase()
      .includes(search.trim().toLowerCase()),
  );
  const targets = (entry?.draft ?? current)
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
  const targetNames = current
    .split(",")
    .filter(Boolean)
    .map(
      (target) => labels.find((item) => item.value === target)?.label ?? target,
    );
  const display =
    name === "targets"
      ? targetNames.length > 3
        ? `${targetNames.length} 项 · ${targetNames.slice(0, 3).join("、")}…`
        : targetNames.join("、")
      : current && name === "confidence"
        ? `${Math.round(Number(current) * 100)}%`
        : current;
  const result = acks.find((ack) => ack.requestId === entry?.request);
  const saving = entry?.status === "pending";
  useEffect(() => {
    if (
      !entry?.request ||
      !["pending", "uncertain"].includes(entry.status ?? "")
    )
      return;
    if (result?.phase === "completed")
      write({
        ...entry,
        status: result.success ? "confirmed" : "failed",
        editing: !result.success,
        message: result.reason,
      });
    else if (result?.phase === "uncertain" || disabled)
      write({
        ...entry,
        status: "uncertain",
        message: result?.reason ?? "连接或节点状态改变，结果待核实",
      });
  }, [
    result?.phase,
    result?.success,
    result?.reason,
    disabled,
    entry?.request,
    entry?.status,
  ]);
  const modelMissing = name === "modelKey" && !node.modelOptions?.length;
  const labelsMissing = name === "targets" && !labels.length;
  const numeric = !["modelKey", "targets"].includes(name);
  // 置信度契约由节点身份决定：Windows 推理节点与 Android 推理节点都是 0.10–0.95 整数百分比（与服务端 ControlProtocol 一致）。
  const integerPercentConfidence =
    node.platform === "windows" || node.component === "android-inference";
  const min =
    name === "confidence" ? (integerPercentConfidence ? 0.1 : 0.01) : 1;
  const max =
    name === "confidence"
      ? integerPercentConfidence
        ? 0.95
        : 1
      : name === "targetSamplingRate"
        ? 5
        : 300;
  const draft = entry?.draft ?? current,
    number = Number(draft);
  const valid =
    name === "modelKey"
      ? !!node.modelOptions?.includes(draft)
      : name === "targets"
        ? targets.length > 0 &&
          targets.every((item) => labels.some((label) => label.value === item))
        : draft.trim() !== "" &&
          Number.isFinite(number) &&
          number >= min &&
          number <= max &&
          (name === "confidence"
            ? !integerPercentConfidence ||
              Math.abs(number * 100 - Math.round(number * 100)) < 0.000001
            : Number.isInteger(number));
  const matches =
    name === "targets"
      ? targets.slice().sort().join(",") ===
        current.split(",").filter(Boolean).sort().join(",")
      : numeric
        ? Number(draft) === Number(current)
        : draft === current;
  const feedback =
    entry?.status === "confirmed"
      ? matches
        ? "已保存"
        : "执行端已保存，等待读回"
      : entry?.status === "pending"
        ? "等待执行端结果…"
        : entry?.message;
  function change(next: string) {
    if (entry) write({ ...entry, draft: next, status: undefined, message: "" });
  }
  function save() {
    if (disabled || saving || !valid || !entry) return;
    const request = send({
      type: "set-config",
      targetDeviceId: node.deviceId,
      ...(source ? { targetSourceId: source.sourceId } : {}),
      key: name,
      value: draft,
    });
    write({
      ...entry,
      request,
      status: request ? "pending" : "failed",
      message: request ? "" : "发送失败，草稿已保留",
    });
  }
  useNavigationDraft(`${node.deviceId}/${source?.sourceId ?? ""}/${name}`, {
    dirty:
      !!entry &&
      (entry.status === "pending" ||
        entry.status === "uncertain" ||
        entry.status === "failed" ||
        (!!entry.editing && !matches)),
    pending: saving,
    canSave: !disabled && !saving && valid,
    save,
    discard: () => write(null),
  });
  return (
    <form
      onKeyDown={(event) => {
        if (event.key === "Escape" && entry?.editing && !saving) {
          event.preventDefault();
          write(null);
        }
      }}
      onSubmit={(event) => {
        event.preventDefault();
        save();
      }}
      className="parameter-field"
    >
      <FieldGroup>
        <div className="parameter-reading">
          <Field>
            <FieldLabel htmlFor={entry?.editing ? id : `${id}-current`}>
              {label}
            </FieldLabel>
          </Field>
          <Input
            id={`${id}-current`}
            readOnly
            title={name === "targets" ? targetNames.join("、") : current}
            aria-label={`${label}当前值`}
            value={display || "未报告"}
          />
          {!entry?.editing && (
            <Button
              ref={editButton}
              type="button"
              aria-label={`${name === "targets" ? "选择" : "编辑"}${label}`}
              disabled={disabled || saving || modelMissing || labelsMissing}
              onClick={() => write({ draft: current, editing: true, model })}
              variant="ghost"
            >
              <Pencil data-icon="inline-start" />
              {name === "targets" ? "选择" : "编辑"}
            </Button>
          )}
        </div>
        {entry?.editing && (
          <div className="parameter-editing">
            {name === "modelKey" ? (
              <NativeSelect
                autoFocus
                id={id}
                value={draft}
                disabled={disabled || saving}
                onChange={(event) => change(event.target.value)}
              >
                {!node.modelOptions?.includes(draft) && (
                  <NativeSelectOption value={draft}>
                    当前模型不可用：{draft || "未报告"}
                  </NativeSelectOption>
                )}
                {node.modelOptions?.map((option) => (
                  <NativeSelectOption key={option}>{option}</NativeSelectOption>
                ))}
              </NativeSelect>
            ) : name === "targets" ? (
              <>
                <Input
                  autoFocus
                  id={id}
                  aria-label="搜索检测目标"
                  placeholder="搜索中文名称或标签"
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                />
                <div className="target-options">
                  {choices.map((item) => (
                    <Field key={item.value}>
                      <FieldLabel>
                        <Checkbox
                          checked={targets.includes(item.value)}
                          disabled={
                            disabled ||
                            saving ||
                            (targets.length === 1 &&
                              targets.includes(item.value))
                          }
                          onCheckedChange={() =>
                            change(
                              targets.includes(item.value)
                                ? targets
                                    .filter((value) => value !== item.value)
                                    .join(",")
                                : [...targets, item.value].join(","),
                            )
                          }
                        />
                        {item.label}
                        <small>{item.value}</small>
                      </FieldLabel>
                    </Field>
                  ))}
                  {!choices.length && (
                    <span className="subtle">无匹配目标</span>
                  )}
                </div>
                <small className="subtle">
                  已选 {targets.length} 项 · 当前模型 {model}
                  {entry.model !== model ? " · 模型已变化，请重新核对" : ""}
                </small>
              </>
            ) : (
              <NumericSelection
                id={id}
                label={label}
                kind={name}
                min={min}
                max={max}
                step={name === "confidence" ? 0.01 : 1}
                value={draft}
                disabled={disabled || saving}
                onChange={change}
              />
            )}
            <div className="actions">
              <Button
                type="submit"
                disabled={disabled || saving || !valid}
                variant="default"
              >
                {saving ? "等待结果…" : "保存"}
              </Button>
              <Button
                type="button"
                disabled={saving}
                onClick={() =>
                  write({
                    ...entry,
                    draft: current,
                    status: undefined,
                    message: "",
                    model,
                  })
                }
                variant="ghost"
              >
                还原
              </Button>
              <Button
                type="button"
                disabled={saving}
                onClick={() => write(null)}
                variant="ghost"
              >
                取消
              </Button>
            </div>
            {!valid && (
              <small className="error">
                {name === "targets"
                  ? "至少一项，且必须属于当前模型"
                  : name === "modelKey"
                    ? "请选择节点可用模型"
                    : `范围 ${min}–${max}${name === "confidence" ? "" : "，整数"}`}
              </small>
            )}
          </div>
        )}
        {(modelMissing || labelsMissing) && (
          <small className="subtle">
            {modelMissing
              ? "节点未报告可用模型"
              : "当前模型未报告标签，不能猜测检测目标"}
          </small>
        )}
        {feedback && (
          <small
            role={entry?.status === "failed" ? "alert" : "status"}
            className={entry?.status === "failed" ? "error" : "subtle"}
          >
            {feedback}
          </small>
        )}
      </FieldGroup>
    </form>
  );
}
