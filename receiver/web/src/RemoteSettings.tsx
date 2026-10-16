import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { FieldGroup, FieldSet } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
  NativeSelect,
  NativeSelectOption,
} from "@/components/ui/native-select";
import { Separator } from "@/components/ui/separator";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import {
  ConfirmDialog,
  EmptyState,
  FormRow,
  SectionCard,
} from "./ConsoleLayout";
import type { Ack, Device } from "./protocol";
import {
  audioImportMetadata,
  MAX_AUDIO_BYTES,
  validAudioName,
} from "./remote-config";
type Props = {
  node: Device;
  connected: boolean;
  acks: Ack[];
  send: (m: Record<string, unknown>) => string;
  section?: "all" | "camera" | "sound" | "audio";
  leading?: ReactNode;
};
function ConfigRow({
  label,
  current,
  options,
  disabled,
  onSave,
}: {
  label: string;
  current: string;
  options: { value: string; label: string }[];
  disabled: boolean;
  onSave: (value: string) => void;
}) {
  const [draft, setDraft] = useState(current);
  const id = useId();
  useEffect(() => setDraft(current), [current]);
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        onSave(draft);
      }}
    >
      <FieldGroup>
        <FormRow
          label={label}
          id={id}
          action={
            <Button
              disabled={
                disabled ||
                draft === current ||
                !options.some((o) => o.value === draft)
              }
              variant="outline"
            >
              保存
            </Button>
          }
        >
          <NativeSelect
            id={id}
            value={draft}
            disabled={disabled}
            onChange={(e) => setDraft(e.target.value)}
          >
            {!options.some((o) => o.value === draft) && (
              <NativeSelectOption value={draft}>
                条目已不可用
              </NativeSelectOption>
            )}
            {options.map((o) => (
              <NativeSelectOption key={o.value} value={o.value}>
                {o.label}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </FormRow>
      </FieldGroup>
    </form>
  );
}
export function RemoteSettings({
  node,
  connected,
  acks,
  send,
  section = "all",
  leading,
}: Props) {
  const active = useRef(true);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
  const [request, setRequest] = useState("");
  const [timedOut, setTimedOut] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [entryId, setEntryId] = useState("");
  const [name, setName] = useState("");
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [importName, setImportName] = useState("");
  const entryField = useId(),
    nameField = useId(),
    fileField = useId(),
    importField = useId();
  const result = acks.find((a) => a.requestId === request);
  const pending =
    loading ||
    (!!request &&
      !timedOut &&
      (!result || result.phase === "pending" || result.phase === "forwarded"));
  const disabled = !connected || !node.online || pending;
  useEffect(() => {
    if (!request) return;
    const timer = setTimeout(() => setTimedOut(true), 30000);
    return () => clearTimeout(timer);
  }, [request]);
  const settings = node.remoteSettings;
  const entries = settings?.audioEntries ?? [];
  const library = entries.filter((e) => e.mutable);
  const entry = library.find((e) => e.id === entryId);
  useEffect(() => {
    setName(entry?.name ?? "");
    setConfirmDelete(false);
  }, [entry?.id, entry?.name]);
  function save(key: string, value: string) {
    setError("");
    setTimedOut(false);
    const id = send({
      type: "set-config",
      targetDeviceId: node.deviceId,
      key,
      value,
    });
    setRequest(id);
    if (!id) setError("发送失败，请重新连接");
  }
  async function importAudio() {
    if (!file || !validAudioName(importName) || disabled) return;
    setLoading(true);
    setError("");
    try {
      const metadata = audioImportMetadata(file);
      const data = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onerror = () => reject(new Error("读取音频失败"));
        reader.onload = () =>
          resolve(String(reader.result).split(",")[1] ?? "");
        reader.readAsDataURL(file);
      });
      if (active.current)
        save(
          "audioImport",
          JSON.stringify({
            name: importName.trim(),
            mime: metadata.mime,
            data,
          }),
        );
    } catch (e) {
      if (active.current) setError((e as Error).message);
    } finally {
      if (active.current) setLoading(false);
    }
  }
  const camera =
    node.capabilities.includes("camera-config") &&
    ["all", "camera"].includes(section);
  const sound =
    node.capabilities.includes("sound-config") &&
    ["all", "sound"].includes(section);
  const audio =
    node.capabilities.includes("audio-library") &&
    ["all", "audio"].includes(section);
  if (!camera && !sound && !audio && !leading) return null;
  const feedback = (
    <>
      {request && (
        <Alert
          variant={
            result?.phase === "completed" && !result.success
              ? "destructive"
              : "default"
          }
        >
          <AlertDescription>
            {result?.phase === "completed"
              ? result.reason || (result.success ? "已保存" : "执行失败")
              : timedOut || result?.phase === "uncertain"
                ? "结果未确认，请核对节点当前配置"
                : result?.phase === "forwarded"
                  ? "已转发，等待节点执行"
                  : "正在发送…"}
          </AlertDescription>
        </Alert>
      )}
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
    </>
  );
  return (
    <div className="section-stack">
      {(camera || sound || leading) && (
        <SectionCard title={camera ? "相机配置" : "报警与声音"}>
          {leading}
          {!settings ? (
            <EmptyState text="等待节点配置" />
          ) : (
            <>
              {camera && (
                <>
                  <ConfigRow
                    label="推流规格"
                    current={settings.cameraResolution ?? ""}
                    options={[
                      { value: "480p", label: "480p" },
                      { value: "720p", label: "720p" },
                    ]}
                    disabled={disabled || node.isMonitoring}
                    onSave={(v) => save("cameraResolution", v)}
                  />
                  {node.isMonitoring && <p role="status">推流中 · 规格只读</p>}
                  <ConfigRow
                    label="本机预览"
                    current={String(settings.cameraHidePreview)}
                    options={[
                      { value: "false", label: "显示" },
                      { value: "true", label: "隐藏" },
                    ]}
                    disabled={disabled}
                    onSave={(v) => save("cameraHidePreview", v)}
                  />
                  <ConfigRow
                    label="推流期间亮度"
                    current={String(settings.cameraDimScreen)}
                    options={[
                      { value: "false", label: "系统亮度" },
                      { value: "true", label: "变暗" },
                    ]}
                    disabled={disabled}
                    onSave={(v) => save("cameraDimScreen", v)}
                  />
                </>
              )}
              {sound && (
                <>
                  <ConfigRow
                    label="默认铃声"
                    current={settings.soundSelection ?? ""}
                    options={entries.map((e) => ({
                      value: e.id,
                      label: e.name,
                    }))}
                    disabled={disabled}
                    onSave={(v) => save("soundSelection", v)}
                  />
                  <ConfigRow
                    label="播放次数"
                    current={String(settings.soundLoopCount)}
                    options={Array.from({ length: 10 }, (_, i) => ({
                      value: String(i + 1),
                      label: `${i + 1} 次`,
                    }))}
                    disabled={disabled}
                    onSave={(v) => save("soundLoopCount", v)}
                  />
                </>
              )}
            </>
          )}
          {!audio && feedback}
        </SectionCard>
      )}
      {audio && (
        <SectionCard title="音频库">
          {!settings ? (
            <EmptyState text="等待节点配置" />
          ) : (
            <FieldSet disabled={disabled}>
              <div className="data-table">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>音频</TableHead>
                      <TableHead>类型</TableHead>
                      <TableHead className="action-heading">操作</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {entries
                      .filter((e) => e.mutable || e.id.startsWith("preset:"))
                      .map((e) => (
                        <TableRow key={e.id}>
                          <TableCell className="audio-name-cell">
                            {e.name}
                            {settings.soundSelection === e.id && (
                              <small>默认铃声</small>
                            )}
                            {settings.audioPreview === e.id && (
                              <small>试听中</small>
                            )}
                          </TableCell>
                          <TableCell>{e.mutable ? "自定义" : "内置"}</TableCell>
                          <TableCell>
                            <div className="table-actions">
                              <Button
                                type="button"
                                variant="outline"
                                aria-label={`在节点试听 ${e.name}`}
                                onClick={() => save("audioPreview", e.id)}
                              >
                                试听
                              </Button>
                              {e.mutable && (
                                <Button
                                  type="button"
                                  variant="ghost"
                                  onClick={() => {
                                    setEntryId(e.id);
                                    document
                                      .getElementById("audio-editor")
                                      ?.scrollIntoView({
                                        block: "center",
                                        behavior: "smooth",
                                      });
                                  }}
                                >
                                  改名
                                </Button>
                              )}
                            </div>
                          </TableCell>
                        </TableRow>
                      ))}
                  </TableBody>
                </Table>
                {!entries.length && <EmptyState text="暂无音频" />}
              </div>
              <div className="actions">
                <Button
                  type="button"
                  variant="ghost"
                  onClick={() => save("audioStopPreview", "")}
                >
                  停止节点试听
                </Button>
              </div>
              <Separator />
              <FieldGroup id="audio-editor">
                <FormRow label="自定义音频" id={entryField}>
                  <NativeSelect
                    id={entryField}
                    value={entry?.id ?? ""}
                    onChange={(e) => setEntryId(e.target.value)}
                  >
                    <NativeSelectOption value="">选择音频</NativeSelectOption>
                    {library.map((e) => (
                      <NativeSelectOption key={e.id} value={e.id}>
                        {e.name}
                      </NativeSelectOption>
                    ))}
                  </NativeSelect>
                </FormRow>
                <FormRow
                  label="音频名称"
                  id={nameField}
                  action={
                    <Button
                      type="button"
                      disabled={
                        !entry ||
                        !validAudioName(name) ||
                        name.trim() === entry.name
                      }
                      onClick={() =>
                        entry &&
                        save(
                          "audioRename",
                          JSON.stringify({ id: entry.id, name: name.trim() }),
                        )
                      }
                      variant="outline"
                    >
                      保存名称
                    </Button>
                  }
                >
                  <Input
                    id={nameField}
                    disabled={!entry}
                    maxLength={64}
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                  />
                </FormRow>
                <div className="form-footer">
                  <Button
                    type="button"
                    disabled={!entry || settings.soundSelection === entry.id}
                    variant="destructive"
                    onClick={() => setConfirmDelete(true)}
                  >
                    删除音频
                  </Button>
                </div>
              </FieldGroup>
              {entry && confirmDelete && (
                <ConfirmDialog
                  title="删除音频"
                  description={`删除“${entry.name}”的节点文件？正在被报警队列使用的音频无法删除。`}
                  confirmText="删除音频"
                  onClose={() => setConfirmDelete(false)}
                  onConfirm={() => {
                    setConfirmDelete(false);
                    save("audioDelete", entry.id);
                  }}
                />
              )}
              <Separator />
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  void importAudio();
                }}
              >
                <FieldGroup>
                  <FormRow label="导入音频" id={fileField}>
                    <Input
                      id={fileField}
                      type="file"
                      accept=".mp3,.wav,.ogg,.m4a"
                      disabled={!!settings.audioLibraryFull}
                      onChange={(e) => {
                        const next = e.target.files?.[0] ?? null;
                        setFile(null);
                        setError("");
                        if (!next) return;
                        try {
                          const metadata = audioImportMetadata(next);
                          setFile(next);
                          setImportName(metadata.name);
                        } catch (error) {
                          setError((error as Error).message);
                          e.target.value = "";
                        }
                      }}
                    />
                  </FormRow>
                  <FormRow label="音频名称" id={importField}>
                    <Input
                      id={importField}
                      maxLength={64}
                      value={importName}
                      onChange={(e) => setImportName(e.target.value)}
                    />
                  </FormRow>
                  <div className="form-footer">
                    <Button
                      disabled={
                        !file ||
                        file.size > MAX_AUDIO_BYTES ||
                        !validAudioName(importName) ||
                        !!settings.audioLibraryFull
                      }
                    >
                      导入节点音频
                    </Button>
                  </div>
                </FieldGroup>
              </form>
              {settings.audioLibraryFull && <p role="status">音频库已满</p>}
            </FieldSet>
          )}
          {feedback}
        </SectionCard>
      )}
    </div>
  );
}
