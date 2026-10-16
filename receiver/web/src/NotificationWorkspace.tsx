import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Field,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
  NativeSelect,
  NativeSelectOption,
} from "@/components/ui/native-select";
import { Separator } from "@/components/ui/separator";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import React, { useEffect, useState } from "react";
import type { Login } from "./account";
import { NodeCacheMaintenance } from "./CacheMaintenance";
import { EmptyState, FormRow, LongPage, SectionCard } from "./ConsoleLayout";
import { DeviceSettings } from "./NodeManagement";
import { AlarmControl, NodeDiagnostics } from "./NodeWorkspace";
import {
  typeLabel,
  type Ack,
  type AlarmTimeZone,
  type Device,
  type Notifier,
  type Scope,
  type Target,
} from "./protocol";
import { RemoteSettings } from "./RemoteSettings";
function ScopeEditor({
  notifier,
  nodes,
  connected,
  acks,
  send,
}: {
  notifier: Notifier;
  nodes: Device[];
  connected: boolean;
  acks: Ack[];
  send: (m: Record<string, unknown>) => string;
}) {
  const [scope, setScope] = useState<Scope>(notifier.scope);
  const [requestId, setRequestId] = useState("");
  useEffect(() => {
    if (!connected) setRequestId("");
  }, [connected]);
  const serverScope = JSON.stringify(notifier.scope);
  useEffect(() => {
    setScope(notifier.scope);
  }, [serverScope]);
  const result = acks.find((ack) => ack.requestId === requestId);
  const saving = result?.phase === "pending" || result?.phase === "forwarded";
  const [offlineDevice, setOfflineDevice] = useState("");
  const [offlineSource, setOfflineSource] = useState("");
  const offlineSourceHintId = React.useId();
  const validOfflineSource = /^[A-Za-z0-9_-]{1,64}$/.test(offlineSource);
  const invalidOfflineSource = !!offlineSource && !validOfflineSource;
  const contains = (target: Target) =>
    scope.targets.some(
      (t) => t.deviceId === target.deviceId && t.sourceId === target.sourceId,
    );
  function updateScope(value: Scope) {
    setScope(value);
    setRequestId("");
  }
  function toggle(target: Target) {
    updateScope({
      ...scope,
      targets: contains(target)
        ? scope.targets.filter(
            (t) =>
              !(
                t.deviceId === target.deviceId && t.sourceId === target.sourceId
              ),
          )
        : [...scope.targets, target],
    });
  }
  return (
    <>
      <FieldSet disabled={!connected || saving}>
        <FieldLegend className="sr-only">接收范围</FieldLegend>
        <ToggleGroup
          type="single"
          variant="outline"
          aria-label="接收范围"
          value={scope.mode}
          onValueChange={(value) => {
            if (value)
              updateScope({ mode: value as Scope["mode"], targets: [] });
          }}
        >
          <ToggleGroupItem value="all">全部节点</ToggleGroupItem>
          <ToggleGroupItem value="selected">指定节点与来源</ToggleGroupItem>
        </ToggleGroup>
        {scope.mode === "selected" && (
          <>
            <div className="scope-targets">
              {nodes.map((node) => (
                <FieldSet key={node.deviceId} className="scope-node">
                  <FieldLegend className="sr-only">
                    {node.deviceName}
                  </FieldLegend>
                  <Field>
                    <FieldLabel>
                      <Checkbox
                        checked={contains({ deviceId: node.deviceId })}
                        onCheckedChange={() =>
                          toggle({ deviceId: node.deviceId })
                        }
                      />
                      {node.deviceName}
                      <small>
                        {typeLabel(node.nodeType, node.component)} ·{" "}
                        {node.online ? "在线" : "离线"}
                      </small>
                    </FieldLabel>
                  </Field>
                  {node.nodeType === "visual" &&
                    [
                      ...new Map(
                        [
                          ...scope.targets
                            .filter(
                              (t) => t.deviceId === node.deviceId && t.sourceId,
                            )
                            .map((t) => ({
                              sourceId: t.sourceId!,
                              sourceName: t.sourceId!,
                            })),
                          ...node.sources.map((source) => ({
                            sourceId: source.sourceId,
                            sourceName: source.sourceName,
                          })),
                        ].map((source) => [source.sourceId, source]),
                      ).values(),
                    ].map((source) => (
                      <Field key={source.sourceId}>
                        <FieldLabel className="source-check">
                          <Checkbox
                            checked={contains({
                              deviceId: node.deviceId,
                              sourceId: source.sourceId,
                            })}
                            onCheckedChange={() =>
                              toggle({
                                deviceId: node.deviceId,
                                sourceId: source.sourceId,
                              })
                            }
                          />
                          {source.sourceName || source.sourceId}
                        </FieldLabel>
                      </Field>
                    ))}
                </FieldSet>
              ))}
            </div>
            <Separator />
            <FieldSet>
              <FieldLegend>未上报的离线来源</FieldLegend>
              <FieldGroup>
                <FormRow label="离线节点" id={`${offlineSourceHintId}-device`}>
                  <NativeSelect
                    id={`${offlineSourceHintId}-device`}
                    value={offlineDevice}
                    onChange={(e) => setOfflineDevice(e.target.value)}
                  >
                    <NativeSelectOption value="">
                      选择视觉推理节点
                    </NativeSelectOption>
                    {nodes
                      .filter(
                        (node) => node.nodeType === "visual" && !node.online,
                      )
                      .map((node) => (
                        <NativeSelectOption
                          key={node.deviceId}
                          value={node.deviceId}
                        >
                          {node.deviceName}
                        </NativeSelectOption>
                      ))}
                  </NativeSelect>
                </FormRow>
                <FormRow
                  label="来源 ID"
                  id={offlineSourceHintId}
                  action={
                    <Button
                      type="button"
                      variant="outline"
                      disabled={
                        !offlineDevice ||
                        !validOfflineSource ||
                        contains({
                          deviceId: offlineDevice,
                          sourceId: offlineSource,
                        })
                      }
                      onClick={() => {
                        toggle({
                          deviceId: offlineDevice,
                          sourceId: offlineSource,
                        });
                        setOfflineSource("");
                      }}
                    >
                      添加
                    </Button>
                  }
                >
                  <Input
                    id={offlineSourceHintId}
                    value={offlineSource}
                    pattern={"[A-Za-z0-9_\\-]{1,64}"}
                    aria-invalid={invalidOfflineSource}
                    aria-describedby={
                      invalidOfflineSource
                        ? `${offlineSourceHintId}-error`
                        : undefined
                    }
                    maxLength={64}
                    onChange={(e) => setOfflineSource(e.target.value)}
                  />
                </FormRow>
                {invalidOfflineSource && (
                  <Alert
                    id={`${offlineSourceHintId}-error`}
                    variant="destructive"
                  >
                    <AlertDescription>
                      来源 ID 只能使用字母、数字、下划线或短横线，最多 64 个字符
                    </AlertDescription>
                  </Alert>
                )}
              </FieldGroup>
            </FieldSet>
            {!scope.targets.length && <p role="status">未选择接收目标</p>}
          </>
        )}
        {scope.targets.length > 100 && (
          <Alert variant="destructive">
            <AlertDescription>接收目标最多 100 项</AlertDescription>
          </Alert>
        )}
        <div className="form-footer">
          <Button
            disabled={scope.targets.length > 100}
            onClick={() =>
              setRequestId(
                send({
                  type: "set-notification-scope",
                  targetNotifierId: notifier.deviceId,
                  scope,
                }),
              )
            }
          >
            {saving ? "保存中…" : "保存接收范围"}
          </Button>
        </div>
      </FieldSet>
      {result && (
        <Alert variant={saving || result.success ? "default" : "destructive"}>
          <AlertDescription>
            {saving
              ? "正在保存接收范围…"
              : result.success
                ? "接收范围已保存"
                : result.reason || "保存接收范围失败"}
          </AlertDescription>
        </Alert>
      )}
    </>
  );
}

export function NotificationWorkspace({
  selected,
  onSelect,
  notifiers,
  nodes,
  login,
  connected,
  acks,
  send,
  timeZone,
  onChanged,
}: {
  selected: string;
  onSelect: (id: string) => void;
  notifiers: Notifier[];
  nodes: Device[];
  login: Login;
  connected: boolean;
  acks: Ack[];
  send: (message: Record<string, unknown>) => string;
  timeZone: AlarmTimeZone;
  onChanged: () => void;
}) {
  const notifier =
    notifiers.find((n) => n.deviceId === selected) ?? notifiers[0];
  const node = nodes.find((n) => n.deviceId === notifier?.deviceId);
  if (!notifier)
    return (
      <div className="page-stack">
        <EmptyState text="尚无已登记的通知节点" />
      </div>
    );
  const controls = node ? { node, connected, acks, send } : null;
  return (
    <LongPage
      sections={[
        {
          id: "notification-scope",
          label: "接收范围",
          content: (
            <SectionCard title="接收范围">
              <FieldGroup>
                <FormRow label="通知节点" id="notifier-select">
                  <NativeSelect
                    id="notifier-select"
                    value={notifier.deviceId}
                    onChange={(e) => onSelect(e.target.value)}
                  >
                    {notifiers.map((item) => (
                      <NativeSelectOption
                        key={item.deviceId}
                        value={item.deviceId}
                      >
                        {item.deviceName}
                        {item.online ? "" : "（离线）"}
                      </NativeSelectOption>
                    ))}
                  </NativeSelect>
                </FormRow>
              </FieldGroup>
              {notifiers.map((item) => (
                <div
                  key={item.deviceId}
                  hidden={item.deviceId !== notifier.deviceId}
                >
                  <ScopeEditor
                    notifier={item}
                    nodes={nodes.filter((n) => n.role === "detector")}
                    connected={connected}
                    acks={acks}
                    send={send}
                  />
                </div>
              ))}
            </SectionCard>
          ),
        },
        ...(controls &&
        node!.capabilities.some((capability) =>
          ["sound-config", "alarm-control"].includes(capability),
        )
          ? [
              {
                id: "notification-sound",
                label: "报警与声音",
                content: (
                  <RemoteSettings
                    key={`sound-${node!.deviceId}`}
                    {...controls}
                    section="sound"
                    leading={<AlarmControl {...controls} />}
                  />
                ),
              },
            ]
          : []),
        ...(controls && node!.capabilities.includes("audio-library")
          ? [
              {
                id: "notification-audio",
                label: "音频库",
                content: (
                  <RemoteSettings
                    key={`audio-${node!.deviceId}`}
                    {...controls}
                    section="audio"
                  />
                ),
              },
            ]
          : []),
        {
          id: "notification-device",
          label: "设备与维护",
          content: node ? (
            <>
              <DeviceSettings
                key={node.deviceId}
                node={node}
                nodes={nodes}
                streams={[]}
                bindings={[]}
                login={login}
                onChanged={onChanged}
              />
              <NodeCacheMaintenance
                node={node}
                connected={connected}
                acks={acks}
                send={send}
              />
              <NodeDiagnostics
                node={node}
                connected={connected}
                acks={acks}
                timeZone={timeZone}
              />
            </>
          ) : (
            <EmptyState text="等待节点状态" />
          ),
        },
      ]}
    />
  );
}
