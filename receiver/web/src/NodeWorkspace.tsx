import { Alert, AlertAction, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { FieldGroup } from "@/components/ui/field";
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
import { useEffect, useRef, useState } from "react";
import { X } from "lucide-react";
import type { Login } from "./account";
import { NodeCacheMaintenance } from "./CacheMaintenance";
import {
  ConfirmDialog,
  EmptyState,
  FormRow,
  LongPage,
  SectionCard,
} from "./ConsoleLayout";
import { useDraftNavigation } from "./DraftNavigation";
import { DeviceSettings } from "./NodeManagement";
import { Parameters, type ParameterDrafts } from "./Parameters";
import {
  formatTime,
  isCameraComponent,
  type Ack,
  type AlarmTimeZone,
  type Binding,
  type Device,
  type Source,
  type Stream,
} from "./protocol";
import { RemoteSettings } from "./RemoteSettings";

export type NodeControlProps = {
  node: Device;
  connected: boolean;
  acks: Ack[];
  send: (message: Record<string, unknown>) => string;
};

export function useNodeControl({
  node,
  connected,
  acks,
  send,
}: NodeControlProps) {
  const [request, setRequest] = useState(""),
    [timedOut, setTimedOut] = useState(false),
    [error, setError] = useState("");
  const result = acks.find((ack) => ack.requestId === request);
  const pending =
    !!request &&
    !timedOut &&
    (!result || ["pending", "forwarded"].includes(result.phase ?? ""));
  useEffect(() => {
    if (!request) return;
    const timer = setTimeout(() => setTimedOut(true), 30000);
    return () => clearTimeout(timer);
  }, [request]);
  const issue = (command: string, targetSourceId?: string) => {
    setTimedOut(false);
    setError("");
    const id = send({
      type: "command",
      targetDeviceId: node.deviceId,
      command,
      ...(targetSourceId ? { targetSourceId } : {}),
    });
    setRequest(id);
    if (!id) setError("发送失败，请重新连接");
  };
  const message =
    result?.phase === "completed"
      ? result.reason || (result.success ? "已执行" : "操作失败")
      : timedOut || result?.phase === "uncertain"
        ? result?.reason || "执行结果未确认，请刷新节点状态"
        : result?.phase === "forwarded"
          ? "已转发，等待节点执行"
          : "正在发送…";
  return {
    issue,
    ready: connected && node.online && !pending,
    feedback: (
      <>
        {request && (
          <Alert
            variant={pending || result?.success ? "default" : "destructive"}
          >
            <AlertDescription>{message}</AlertDescription>
          </Alert>
        )}
        {error && (
          <Alert variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}
      </>
    ),
  };
}

export function AlarmControl(props: NodeControlProps) {
  const control = useNodeControl(props);
  return (
    <>
      {props.node.capabilities.includes("alarm-control") && (
        <div className="actions">
          <Button
            disabled={!control.ready}
            onClick={() => control.issue("stop-alarm")}
          >
            确认当前报警
          </Button>
        </div>
      )}
      {control.feedback}
    </>
  );
}

export function NodeWorkspace({
  node,
  nodes,
  streams,
  bindings,
  login,
  connected,
  acks,
  send,
  drafts,
  timeZone,
  onChanged,
  onEvents,
}: NodeControlProps & {
  nodes: Device[];
  streams: Stream[];
  bindings: Binding[];
  login: Login;
  drafts: ParameterDrafts;
  timeZone: AlarmTimeZone;
  onChanged: () => void;
  onEvents: () => void;
}) {
  const navigate = useDraftNavigation();
  const [sourceId, setSourceId] = useState("");
  const [deleting, setDeleting] = useState<Source | null>(null);
  const [sourceError, setSourceError] = useState("");
  const sourceErrorRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (sourceError)
      sourceErrorRef.current?.scrollIntoView({
        block: "nearest",
        behavior: matchMedia("(prefers-reduced-motion: reduce)").matches
          ? "instant"
          : "smooth",
      });
  }, [sourceError]);
  const { issue, ready, feedback } = useNodeControl({
    node,
    connected,
    acks,
    send,
  });
  const can = (capability: string) => node.capabilities.includes(capability);
  const camera = isCameraComponent(node.component),
    publisher = can("video-publish");
  const source =
    node.sources.find((item) => item.sourceId === sourceId) ?? node.sources[0];
  const published = streams.filter(
    (item) => item.publisherDeviceId === node.deviceId,
  );
  const cameraStartReason = !publisher
    ? ""
    : !bindings.some(
          (item) =>
            item.publisherDeviceId === node.deviceId && item.targetDeviceId,
        )
      ? "未关联视觉推理节点"
      : camera && node.components?.cameraApp !== "foreground"
        ? "相机应用未在前台"
        : camera && node.components?.cameraPermission !== "granted"
          ? "摄像头权限未授权"
          : "";
  const sourceControls = (
    <div className="actions">
      {can("monitor-control") && (
        <>
          <Button
            disabled={!ready}
            onClick={() => issue(node.isMonitoring ? "pause" : "resume")}
          >
            {node.sources.length
              ? node.isMonitoring
                ? "暂停全部来源"
                : "开始全部来源"
              : node.isMonitoring
                ? "暂停检测"
                : "开始检测"}
          </Button>
          <Button
            disabled={!ready}
            variant="outline"
            onClick={() => issue("stop-alarm")}
          >
            停止报警
          </Button>
        </>
      )}
      {can("stream-control") && (
        <>
          <Button
            disabled={
              !ready ||
              (published.length > 0 &&
                published.every((item) => item.isStreaming)) ||
              !!cameraStartReason
            }
            onClick={() => issue("start-stream")}
          >
            开始推流
          </Button>
          <Button
            disabled={!ready}
            variant="outline"
            onClick={() => issue("stop-stream")}
          >
            停止推流
          </Button>
        </>
      )}
    </div>
  );
  const openRules = (id: string) =>
    navigate(() => {
      setSourceId(id);
      document
        .getElementById("node-rules")
        ?.scrollIntoView({ block: "start", behavior: "smooth" });
    });
  const rules = can("config-control") && (!can("source-control") || !!source);
  return (
    <>
      <LongPage
        sections={[
          {
            id: "node-sources",
            label: camera || publisher ? "来源与推流" : "来源与检测",
            content: (
              <>
                <SectionCard
                  title={camera || publisher ? "来源与推流" : "来源与检测"}
                  action={sourceControls}
                >
                  {(node.sourceLimitExceeded || node.overInferenceLimit) && (
                    <Alert variant="destructive">
                      <AlertDescription>
                        {node.sourceLimitExceeded &&
                          `来源数量超过上限（最多 ${node.maxSources ?? "未报告"} 路），列表为最后成功上报的快照。`}
                        {node.overInferenceLimit &&
                          "运行来源超过本机推理能力，请减少同时运行的来源。"}
                      </AlertDescription>
                    </Alert>
                  )}
                  {sourceError && (
                    <Alert ref={sourceErrorRef} variant="destructive">
                      <AlertDescription>{sourceError}</AlertDescription>
                      <AlertAction>
                        <Button
                          variant="ghost"
                          size="icon"
                          aria-label="关闭来源异常"
                          onClick={() => setSourceError("")}
                        >
                          <X />
                        </Button>
                      </AlertAction>
                    </Alert>
                  )}
                  {node.nodeType === "sensor" && (
                    <dl>
                      <dt>检测状态</dt>
                      <dd>
                        {!node.online
                          ? "离线"
                          : node.isMonitoring
                            ? "检测中"
                            : node.isReady
                              ? "已就绪"
                              : "未就绪"}
                      </dd>
                    </dl>
                  )}
                  {!camera && node.nodeType === "visual" && (
                    <>
                      {!node.sources.length ? (
                        <EmptyState text="暂无来源" />
                      ) : (
                        <div className="data-table">
                          <Table>
                            <TableHeader>
                              <TableRow>
                                <TableHead>来源</TableHead>
                                <TableHead>状态</TableHead>
                                <TableHead>实际 FPS</TableHead>
                                <TableHead className="action-heading">
                                  操作
                                </TableHead>
                              </TableRow>
                            </TableHeader>
                            <TableBody>
                              {node.sources.map((item) => (
                                <TableRow
                                  key={item.sourceId}
                                  data-state={
                                    source?.sourceId === item.sourceId
                                      ? "selected"
                                      : undefined
                                  }
                                >
                                  <TableCell className="source-name-cell">
                                    {item.sourceName || item.sourceId}
                                  </TableCell>
                                  <TableCell>
                                    <Badge variant="secondary">
                                      {!node.online
                                        ? "离线"
                                        : item.error
                                          ? "异常"
                                          : !item.isReady
                                            ? "未就绪"
                                            : item.isMonitoring
                                              ? "检测中"
                                              : "已暂停"}
                                    </Badge>
                                  </TableCell>
                                  <TableCell
                                    title={
                                      item.concurrencyLimit
                                        ? `最多 ${item.concurrencyLimit} 路（单路 ≥5 FPS） · ${item.activeBackend ?? ""} ${item.backendNote ?? ""}`
                                        : undefined
                                    }
                                  >
                                    {!node.online ||
                                    item.actualFps === undefined
                                      ? "未报告"
                                      : item.actualFps.toFixed(1)}
                                  </TableCell>
                                  <TableCell>
                                    <div className="table-actions">
                                      {can("source-control") &&
                                        can("monitor-control") && (
                                          <Button
                                            disabled={!ready}
                                            variant="outline"
                                            onClick={() =>
                                              issue(
                                                item.isMonitoring
                                                  ? "pause"
                                                  : "resume",
                                                item.sourceId,
                                              )
                                            }
                                            aria-label={`${item.isMonitoring ? "暂停" : "开始"} ${item.sourceName}`}
                                          >
                                            {item.isMonitoring
                                              ? "暂停"
                                              : "开始"}
                                          </Button>
                                        )}
                                      {item.error && (
                                        <Button
                                          variant="ghost"
                                          onClick={() =>
                                            setSourceError(
                                              `${item.sourceName || item.sourceId}：${item.error ?? ""}`,
                                            )
                                          }
                                        >
                                          查看原因
                                        </Button>
                                      )}
                                      {rules && (
                                        <Button
                                          variant="ghost"
                                          onClick={() =>
                                            openRules(item.sourceId)
                                          }
                                        >
                                          定位规则
                                        </Button>
                                      )}
                                      {can("source-control") && (
                                        <Button
                                          variant="destructive"
                                          disabled={!ready || item.isMonitoring}
                                          onClick={() => setDeleting(item)}
                                        >
                                          删除
                                        </Button>
                                      )}
                                    </div>
                                  </TableCell>
                                </TableRow>
                              ))}
                            </TableBody>
                          </Table>
                        </div>
                      )}
                    </>
                  )}
                  {publisher && (
                    <div className="data-table">
                      <Table>
                        <TableHeader>
                          <TableRow>
                            <TableHead>推流来源</TableHead>
                            <TableHead>状态</TableHead>
                            <TableHead>最近画面</TableHead>
                          </TableRow>
                        </TableHeader>
                        <TableBody>
                          {published.map((item) => (
                            <TableRow key={item.streamId}>
                              <TableCell>
                                {item.sourceName || item.publisherName}
                              </TableCell>
                              <TableCell>
                                {item.isStreaming
                                  ? "推流中"
                                  : item.stopReason
                                    ? `已停推 · ${item.stopReason === "connection-lost" ? "连接断开" : item.stopReason === "frame-stalled" ? "画面停滞" : item.stopReason === "user" ? "本机停止" : "已停止"}`
                                    : "等待推流"}
                              </TableCell>
                              <TableCell>
                                {item.lastFrameAt
                                  ? formatTime(item.lastFrameAt, timeZone)
                                  : "未报告"}
                              </TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                      {!published.length && <EmptyState text="暂无推流" />}
                    </div>
                  )}
                  {can("stream-control") && cameraStartReason && (
                    <p role="status">{cameraStartReason}</p>
                  )}
                  {feedback}
                  <div className="actions">
                    <Button variant="ghost" onClick={onEvents}>
                      查看节点事件
                    </Button>
                  </div>
                </SectionCard>
                {camera && (
                  <RemoteSettings
                    node={node}
                    connected={connected}
                    acks={acks}
                    send={send}
                    section="camera"
                  />
                )}
              </>
            ),
          },
          ...(rules
            ? [
                {
                  id: "node-rules",
                  label: "检测规则",
                  content: (
                    <SectionCard title="检测规则">
                      {can("source-control") && source && (
                        <FieldGroup>
                          <FormRow
                            label="当前来源"
                            id="current-source"
                            action={
                              <span
                                className="parameter-action-space"
                                aria-hidden="true"
                              />
                            }
                          >
                            <NativeSelect
                              id="current-source"
                              value={source.sourceId}
                              onChange={(e) =>
                                navigate(() => setSourceId(e.target.value))
                              }
                            >
                              {node.sources.map((item) => (
                                <NativeSelectOption
                                  key={item.sourceId}
                                  value={item.sourceId}
                                >
                                  {item.sourceName || item.sourceId}
                                </NativeSelectOption>
                              ))}
                            </NativeSelect>
                          </FormRow>
                        </FieldGroup>
                      )}
                      <Parameters
                        key={`${node.deviceId}/${can("source-control") ? source?.sourceId : ""}`}
                        embedded
                        node={node}
                        source={can("source-control") ? source : undefined}
                        acks={acks}
                        drafts={drafts}
                        disabled={
                          !ready ||
                          (can("source-control")
                            ? !!source?.isMonitoring
                            : node.isMonitoring)
                        }
                        send={send}
                      />
                      {source && (
                        <>
                          <Separator />
                          <dl>
                            <dt>来源 ID</dt>
                            <dd>{source.sourceId}</dd>
                            <dt>实际运行后端</dt>
                            <dd>{source.activeBackend || "未报告"}</dd>
                            <dt>后端说明</dt>
                            <dd>{source.backendNote || "未报告"}</dd>
                            <dt>并发能力</dt>
                            <dd>
                              {source.concurrencyLimit === undefined
                                ? "未报告"
                                : `${source.concurrencyLimit} 路（单路 ≥5 FPS）`}
                            </dd>
                          </dl>
                        </>
                      )}
                    </SectionCard>
                  ),
                },
              ]
            : []),
          {
            id: "node-device",
            label: "设备与关联",
            content: (
              <>
                <DeviceSettings
                  node={node}
                  nodes={nodes}
                  streams={streams}
                  bindings={bindings}
                  login={login}
                  onChanged={onChanged}
                />
                {!camera && (
                  <RemoteSettings
                    node={node}
                    connected={connected}
                    acks={acks}
                    send={send}
                  />
                )}
              </>
            ),
          },
          {
            id: "node-diagnostics",
            label: "维护与诊断",
            content: (
              <>
                <NodeDiagnostics
                  node={node}
                  streams={published}
                  connected={connected}
                  acks={acks}
                  timeZone={timeZone}
                />
                {can("app-lifecycle-control") && (
                  <SectionCard title="应用维护">
                    <div className="actions">
                      <Button
                        variant="outline"
                        disabled={!ready}
                        onClick={() => issue("open-detector")}
                      >
                        打开视觉推理节点
                      </Button>
                      <Button
                        variant="outline"
                        disabled={!ready}
                        onClick={() => issue("close-detector")}
                      >
                        关闭视觉推理节点
                      </Button>
                    </div>
                  </SectionCard>
                )}
                <NodeCacheMaintenance
                  node={node}
                  connected={connected}
                  acks={acks}
                  send={send}
                />
              </>
            ),
          },
        ]}
      />
      {deleting && (
        <ConfirmDialog
          title="删除来源"
          description={`删除“${deleting.sourceName}”？远程来源会解除与发布端的绑定，该发布端的来源将一起移除。`}
          confirmText="删除来源"
          onClose={() => setDeleting(null)}
          onConfirm={() => {
            issue("remove-source", deleting.sourceId);
            setDeleting(null);
          }}
        />
      )}
    </>
  );
}

export function NodeDiagnostics({
  node,
  streams = [],
  connected,
  acks,
  timeZone,
}: {
  node: Device;
  streams?: Stream[];
  connected: boolean;
  acks: Ack[];
  timeZone: AlarmTimeZone;
}) {
  const records = acks.filter((ack) => ack.targetDeviceId === node.deviceId);
  return (
    <>
      <SectionCard title="节点状态">
        <dl>
          <dt>控制连接</dt>
          <dd>
            {connected
              ? node.online
                ? "节点在线"
                : "节点离线"
              : "控制台连接已断开"}
          </dd>
          <dt>媒体状态</dt>
          <dd>
            {streams.length
              ? `${streams.filter((s) => s.isStreaming).length} / ${streams.length} 路推流中`
              : "未报告"}
          </dd>
          {Object.entries(node.components ?? {}).map(([key, value]) => (
            <div className="reading-pair" key={key}>
              <dt>
                {(
                  {
                    resident: "驻留",
                    residentApp: "驻留",
                    detector: "视觉推理节点",
                    detectorApp: "视觉推理节点",
                    camera: "镜头",
                    cameraApp: "相机状态",
                    cameraPermission: "摄像头权限",
                  } as Record<string, string>
                )[key] ?? key}
              </dt>
              <dd>
                {(
                  {
                    running: "运行中",
                    stopped: "已停止",
                    foreground: "前台",
                    background: "后台",
                    granted: "已授权",
                    required: "待授权",
                  } as Record<string, string>
                )[value] ?? value}
              </dd>
            </div>
          ))}
          <dt>最近响应</dt>
          <dd>
            {node.lastSeen ? formatTime(node.lastSeen, timeZone) : "未报告"}
          </dd>
        </dl>
      </SectionCard>
      <SectionCard title="操作回执">
        <div className="data-table">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>操作</TableHead>
                <TableHead>控制对象</TableHead>
                <TableHead>状态</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {records.map((ack) => (
                <TableRow key={ack.requestId}>
                  <TableCell>{commandLabel(ack.command)}</TableCell>
                  <TableCell>{ack.targetSourceId || "节点整体"}</TableCell>
                  <TableCell className="receipt-state">
                    {ack.phase === "uncertain"
                      ? "结果待核实"
                      : ack.phase === "forwarded"
                        ? "已转发"
                        : ack.phase === "pending"
                          ? "等待执行"
                          : ack.success
                            ? "已完成"
                            : "失败"}
                    {ack.reason && <small>{ack.reason}</small>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {!records.length && <EmptyState text="暂无操作回执" />}
        </div>
      </SectionCard>
    </>
  );
}

function commandLabel(command?: string) {
  if (command?.startsWith("set-config:"))
    return `保存${({ modelKey: "推理模型", confidence: "置信度", cooldown: "报警冷却", targetSamplingRate: "采样频率", targets: "检测目标", cameraResolution: "推流规格", cameraHidePreview: "预览设置", cameraDimScreen: "屏幕亮度", soundSelection: "默认铃声", soundLoopCount: "播放次数", audioRename: "音频名称", audioImport: "导入音频", audioDelete: "删除音频", audioPreview: "音频试听", audioStopPreview: "停止试听" } as Record<string, string>)[command.slice(11)] || "节点配置"}`;
  return (
    (
      {
        "remove-source": "删除来源",
        pause: "暂停检测",
        resume: "开始检测",
        "stop-alarm": "停止 / 确认报警",
        "start-stream": "开始推流",
        "stop-stream": "停止推流",
        "open-detector": "打开视觉推理节点",
        "close-detector": "关闭视觉推理节点",
        "set-config": "保存配置",
        "set-notification-scope": "保存通知范围",
        "cache-inspect": "盘点缓存",
        "cache-clean": "清理缓存",
      } as Record<string, string>
    )[command ?? ""] || "节点操作"
  );
}
