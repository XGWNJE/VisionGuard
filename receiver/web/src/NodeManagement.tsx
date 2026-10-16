import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { FieldGroup } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
  NativeSelect,
  NativeSelectOption,
} from "@/components/ui/native-select";
import { Separator } from "@/components/ui/separator";
import { useEffect, useState } from "react";
import { accountRequest, type Login } from "./account";
import { ConfirmDialog, FormRow, SectionCard } from "./ConsoleLayout";
import { checkNamePaste } from "./nameInput";
import {
  isCameraComponent,
  type Binding,
  type Device,
  type Stream,
} from "./protocol";
export function DeviceSettings({
  node,
  nodes,
  streams,
  bindings,
  login,
  onChanged,
}: {
  node: Device;
  nodes: Device[];
  streams: Stream[];
  bindings: Binding[];
  login: Login;
  onChanged: () => void;
}) {
  const [name, setName] = useState(node.deviceName);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [confirmUnbind, setConfirmUnbind] = useState(false);
  // 绑定与流分开：目标取自 bindings（发布端还没有来源时也只有它知道），流列出每个来源。
  const boundTarget =
    bindings.find((b) => b.publisherDeviceId === node.deviceId)
      ?.targetDeviceId ?? "";
  const sources = streams.filter((s) => s.publisherDeviceId === node.deviceId);
  const pushing = sources.some((s) => s.isStreaming);
  const [target, setTarget] = useState(boundTarget);
  useEffect(() => setTarget(boundTarget), [boundTarget]);
  useEffect(() => setName(node.deviceName), [node.deviceName]);
  const camera =
    isCameraComponent(node.component) ||
    node.capabilities.includes("video-publish");
  const targets = nodes.filter(
    (n) =>
      n.deviceId !== node.deviceId &&
      (n.component === "windows-inference" ||
        n.component === "android-inference" ||
        n.capabilities.includes("video-subscribe")),
  );
  const incoming = bindings.filter((b) => b.targetDeviceId === node.deviceId);
  async function mutate(path: string, body: unknown, method?: string) {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await accountRequest(path, login.token, body, method);
      setMessage(method === "DELETE" ? "设备已解绑，需要重新登录" : "已保存");
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function unbindTarget(publisherDeviceId = node.deviceId) {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await accountRequest("/api/streams/unbind", login.token, {
        publisherDeviceId,
      });
      setMessage("已解除推流绑定");
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <SectionCard title="设备与关联">
      <dl>
        <dt>设备 ID</dt>
        <dd>{node.deviceId}</dd>
        <dt>组件</dt>
        <dd>{node.component || "未报告"}</dd>
      </dl>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          void mutate(
            "/api/devices/" + encodeURIComponent(node.deviceId),
            { deviceName: name },
            "PATCH",
          );
        }}
      >
        <FieldGroup>
          <FormRow
            label="设备名称"
            id="node-device-name"
            action={
              <Button disabled={busy || !name.trim()} variant="outline">
                {busy ? "保存中…" : "保存名称"}
              </Button>
            }
          >
            <Input
              id="node-device-name"
              required
              maxLength={64}
              value={name}
              onPaste={(e) => checkNamePaste(e, setError)}
              onChange={(e) => {
                setError("");
                setName(e.target.value);
              }}
              disabled={busy}
            />
          </FormRow>
        </FieldGroup>
      </form>
      {camera && (
        <>
          <Separator />
          <h3>推流关联</h3>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void mutate("/api/streams/bind", {
                publisherDeviceId: node.deviceId,
                targetDeviceId: target,
              });
            }}
          >
            <FieldGroup>
              <FormRow label="推理节点" id="stream-target">
                <NativeSelect
                  id="stream-target"
                  value={target}
                  onChange={(e) => setTarget(e.target.value)}
                  disabled={busy}
                >
                  <NativeSelectOption value="">选择推理节点</NativeSelectOption>
                  {targets.map((t) => (
                    <NativeSelectOption key={t.deviceId} value={t.deviceId}>
                      {t.deviceName}
                      {t.online ? "" : "（离线）"}
                    </NativeSelectOption>
                  ))}
                </NativeSelect>
              </FormRow>
              <div className="form-footer">
                <Button
                  type="button"
                  disabled={busy || !boundTarget}
                  onClick={() => void unbindTarget()}
                  variant="outline"
                >
                  解除绑定
                </Button>
                <Button disabled={busy || !target}>关联目标</Button>
              </div>
              <p role="status">
                {pushing
                  ? "推流中"
                  : boundTarget
                    ? "已关联，等待推流"
                    : "未关联"}
              </p>
            </FieldGroup>
          </form>
        </>
      )}
      {incoming.length > 0 && (
        <>
          <Separator />
          <h3>接收的推流来源</h3>
          {incoming.map((binding) => (
            <div className="setting-line" key={binding.publisherDeviceId}>
              <span>
                {nodes.find(
                  (item) => item.deviceId === binding.publisherDeviceId,
                )?.deviceName || binding.publisherDeviceId}
              </span>
              <Button
                disabled={busy}
                onClick={() => void unbindTarget(binding.publisherDeviceId)}
                variant="outline"
              >
                解除来源绑定
              </Button>
            </div>
          ))}
        </>
      )}
      <Separator />
      <div className="actions">
        <Button
          disabled={busy}
          onClick={() => setConfirmUnbind(true)}
          variant="destructive"
        >
          解绑设备
        </Button>
      </div>
      {confirmUnbind && (
        <ConfirmDialog
          title="解绑设备"
          description={
            "解绑“" +
            node.deviceName +
            "”后，该设备需要重新登录。事件记录会保留。"
          }
          confirmText="解绑"
          onClose={() => setConfirmUnbind(false)}
          onConfirm={() => {
            setConfirmUnbind(false);
            void mutate(
              "/api/devices/" + encodeURIComponent(node.deviceId),
              undefined,
              "DELETE",
            );
          }}
        />
      )}
      {busy && <p role="status">正在处理…</p>}
      {message && <p role="status">{message}</p>}
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
    </SectionCard>
  );
}
