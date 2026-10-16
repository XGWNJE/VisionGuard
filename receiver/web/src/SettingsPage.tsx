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
import { accountRequest, normalizeDisplayName, type Login } from "./account";
import { AppearanceSelector, useAppearance } from "./appearance";
import { ServerCacheMaintenance } from "./CacheMaintenance";
import { FormRow, LongPage, SectionCard } from "./ConsoleLayout";
import { checkNamePaste } from "./nameInput";
import {
  formatTime,
  timeStandardLabel,
  type Ack,
  type AlarmTimeZone,
  type TimeStandard,
} from "./protocol";
import { clearRememberedLogin } from "./rememberedLogin";

type Props = {
  login: Login;
  onLogout: () => void;
  onPasswordChanged: (warning?: string) => void;
  onDeviceNameChanged: (name: string) => void;
  standard: TimeStandard | null;
  connected: boolean;
  acks: Ack[];
  send: (m: Record<string, unknown>) => string;
};

export function SettingsPage({
  login,
  onLogout,
  onPasswordChanged,
  onDeviceNameChanged,
  standard,
  connected,
  acks,
  send,
}: Props) {
  const appearance = useAppearance();
  const [deviceName, setDeviceName] = useState(login.device.deviceName);
  const [nameBusy, setNameBusy] = useState(false),
    [nameMessage, setNameMessage] = useState(""),
    [nameError, setNameError] = useState("");
  const [currentPassword, setCurrentPassword] = useState(""),
    [newPassword, setNewPassword] = useState("");
  const [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  useEffect(
    () => setDeviceName(login.device.deviceName),
    [login.device.deviceName],
  );
  async function renameSelf() {
    setNameBusy(true);
    setNameMessage("");
    setNameError("");
    try {
      const name = normalizeDisplayName(deviceName);
      await accountRequest(
        `/api/devices/${encodeURIComponent(login.device.deviceId)}`,
        login.token,
        { deviceName: name },
        "PATCH",
      );
      onDeviceNameChanged(name);
      setNameMessage("名称已保存");
    } catch (e) {
      setNameError((e as Error).message);
    } finally {
      setNameBusy(false);
    }
  }
  async function changePassword() {
    setBusy(true);
    setError("");
    try {
      await accountRequest("/api/account/password", login.token, {
        currentPassword,
        newPassword,
      });
      let warning: string | undefined;
      try {
        await clearRememberedLogin();
      } catch {
        warning = "密码已修改，但旧账号密码记忆未能清除；请使用新密码重新登录";
      }
      setCurrentPassword("");
      setNewPassword("");
      onPasswordChanged(warning);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <LongPage
      sections={[
        {
          id: "account-security",
          label: "账号与安全",
          content: (
            <SectionCard title="账号与安全">
              <dl>
                <dt>当前账号</dt>
                <dd>{login.account.username}</dd>
                <dt>账号权限</dt>
                <dd>{login.account.isAdmin ? "管理员" : "普通账号"}</dd>
              </dl>
              <Separator />
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  void changePassword();
                }}
              >
                <FieldGroup>
                  <FormRow label="当前密码" id="current-password">
                    <Input
                      id="current-password"
                      required
                      type="password"
                      maxLength={256}
                      autoComplete="current-password"
                      value={currentPassword}
                      onChange={(e) => setCurrentPassword(e.target.value)}
                      disabled={busy}
                    />
                  </FormRow>
                  <FormRow label="新密码" id="new-password">
                    <Input
                      id="new-password"
                      required
                      type="password"
                      minLength={8}
                      maxLength={256}
                      autoComplete="new-password"
                      value={newPassword}
                      onChange={(e) => setNewPassword(e.target.value)}
                      disabled={busy}
                    />
                  </FormRow>
                  <div className="form-footer">
                    <Button disabled={busy}>
                      {busy ? "保存中…" : "修改密码"}
                    </Button>
                  </div>
                  {error && (
                    <Alert variant="destructive">
                      <AlertDescription>{error}</AlertDescription>
                    </Alert>
                  )}
                </FieldGroup>
              </form>
              <Separator />
              <div className="actions">
                <Button variant="outline" onClick={onLogout}>
                  退出登录
                </Button>
              </div>
            </SectionCard>
          ),
        },
        {
          id: "local-appearance",
          label: "本机与外观",
          content: (
            <SectionCard title="本机与外观">
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  void renameSelf();
                }}
              >
                <FieldGroup>
                  <FormRow
                    label="本机名称"
                    id="console-device-name"
                    action={
                      <Button
                        variant="outline"
                        disabled={nameBusy || !deviceName.trim()}
                      >
                        {nameBusy ? "保存中…" : "保存名称"}
                      </Button>
                    }
                  >
                    <Input
                      id="console-device-name"
                      required
                      maxLength={64}
                      value={deviceName}
                      onPaste={(e) => checkNamePaste(e, setNameError)}
                      onChange={(e) => {
                        setNameError("");
                        setDeviceName(e.target.value);
                      }}
                      disabled={nameBusy}
                    />
                  </FormRow>
                  {nameMessage && <p role="status">{nameMessage}</p>}
                  {nameError && (
                    <Alert variant="destructive">
                      <AlertDescription>{nameError}</AlertDescription>
                    </Alert>
                  )}
                </FieldGroup>
              </form>
              <FieldGroup>
                <FormRow label="外观">
                  <AppearanceSelector
                    preference={appearance}
                    showIcons={false}
                  />
                </FormRow>
              </FieldGroup>
            </SectionCard>
          ),
        },
        {
          id: "service-maintenance",
          label: "服务与维护",
          content: (
            <>
              <SectionCard title="服务与维护">
                <dl>
                  <dt>服务地址</dt>
                  <dd>{location.origin}</dd>
                  <dt>当前时间标准</dt>
                  <dd>
                    {standard
                      ? timeStandardLabel(standard.timeZone)
                      : "等待服务响应"}
                  </dd>
                  <dt>服务参考时间</dt>
                  <dd>
                    {standard
                      ? formatTime(standard.serverTime, standard.timeZone)
                      : "未报告"}
                  </dd>
                </dl>
                <TimeStandardSettings
                  standard={standard}
                  connected={connected}
                  acks={acks}
                  send={send}
                />
              </SectionCard>
              <ServerCacheMaintenance login={login} />
            </>
          ),
        },
      ]}
    />
  );
}

function TimeStandardSettings({
  standard,
  connected,
  acks,
  send,
}: Pick<Props, "standard" | "connected" | "acks" | "send">) {
  const [draft, setDraft] = useState<AlarmTimeZone>(
    standard?.timeZone ?? "Asia/Shanghai",
  );
  const [requestId, setRequestId] = useState("");
  useEffect(() => {
    if (!connected) setRequestId("");
  }, [connected]);
  useEffect(() => {
    if (standard) setDraft(standard.timeZone);
  }, [standard?.timeZone]);
  const result = acks.find((ack) => ack.requestId === requestId);
  const saving = result?.phase === "pending" || result?.phase === "forwarded";
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        setRequestId(send({ type: "set-time-standard", timeZone: draft }));
      }}
    >
      <FieldGroup>
        <FormRow
          label="时间标准"
          id="console-time-zone"
          action={
            <Button disabled={!connected || !standard || saving}>
              {saving ? "保存中…" : "保存标准"}
            </Button>
          }
        >
          <NativeSelect
            id="console-time-zone"
            value={draft}
            onChange={(e) => setDraft(e.target.value as AlarmTimeZone)}
            disabled={!connected || !standard || saving}
          >
            <NativeSelectOption value="Asia/Shanghai">
              北京时间（UTC+8）
            </NativeSelectOption>
            <NativeSelectOption value="UTC">UTC（UTC+0）</NativeSelectOption>
          </NativeSelect>
        </FormRow>
        {result && (
          <Alert variant={saving || result.success ? "default" : "destructive"}>
            <AlertDescription>
              {saving
                ? "正在保存…"
                : result.success
                  ? "时间标准已保存"
                  : result.reason || "保存失败"}
            </AlertDescription>
          </Alert>
        )}
      </FieldGroup>
    </form>
  );
}
