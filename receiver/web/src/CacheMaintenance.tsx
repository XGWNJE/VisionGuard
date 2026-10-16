import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { FieldGroup } from "@/components/ui/field";
import {
  NativeSelect,
  NativeSelectOption,
} from "@/components/ui/native-select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useEffect, useRef, useState } from "react";
import { FormRow, SectionCard } from "./ConsoleLayout";
import { accountRequest, type Login } from "./account";
import type { Ack, Device } from "./protocol";
export type CacheReport = {
  categories: {
    id: string;
    label: string;
    files: number;
    bytes: number;
    cleanableFiles: number;
    cleanableBytes: number;
  }[];
  removedFiles: number;
  removedBytes: number;
  releasedBytes: number | null;
  failedFiles: number;
};
const size = (bytes: number) =>
  bytes >= 1024 * 1024
    ? `${(bytes / 1024 / 1024).toFixed(1)} MB`
    : `${(bytes / 1024).toFixed(1)} KB`;

function Report({
  report,
  cleaned = false,
}: {
  report: CacheReport;
  cleaned?: boolean;
}) {
  return (
    <>
      <div className="data-table">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>类别</TableHead>
              <TableHead>当前大小 / 文件</TableHead>
              <TableHead>可清理</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {report.categories.map((c) => (
              <TableRow key={c.id}>
                <TableCell>{c.label}</TableCell>
                <TableCell>
                  {size(c.bytes)} · {c.files} 个
                </TableCell>
                <TableCell>
                  {size(c.cleanableBytes)} · {c.cleanableFiles} 个
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      {cleaned && (
        <Alert variant={report.failedFiles ? "destructive" : "default"}>
          <AlertDescription>
            已删除 {report.removedFiles} 个（{size(report.removedBytes)}）；
            {report.releasedBytes === null
              ? "磁盘释放量未计量"
              : "释放 " + size(report.releasedBytes)}
            {report.failedFiles > 0 &&
              "；" + report.failedFiles + " 个失败，请重新盘点"}
          </AlertDescription>
        </Alert>
      )}
    </>
  );
}
export function ServerCacheMaintenance({ login }: { login: Login }) {
  const [scope, setScope] = useState("account"),
    [report, setReport] = useState<CacheReport | null>(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const generation = useRef(0);
  const [cleaned, setCleaned] = useState(false);
  useEffect(() => {
    generation.current++;
    setReport(null);
    setError("");
    setBusy(false);
    return () => {
      generation.current++;
    };
  }, [login.token, scope]);
  async function run(clean: boolean) {
    const own = ++generation.current;
    setBusy(true);
    setError("");
    try {
      const next = await accountRequest<CacheReport>(
        clean ? "/api/cache" : `/api/cache?scope=${scope}`,
        login.token,
        clean ? { scope } : undefined,
      );
      if (own === generation.current) {
        setReport(next);
        setCleaned(clean);
      }
    } catch (e) {
      if (own === generation.current) {
        setReport(null);
        setError((e as Error).message + "；结果未确认时，请重新盘点。");
      }
    } finally {
      if (own === generation.current) setBusy(false);
    }
  }
  return (
    <SectionCard title="服务缓存">
      <dl>
        <dt>截图保留</dt>
        <dd>72 小时</dd>
      </dl>
      {login.account.isAdmin && (
        <FieldGroup>
          <FormRow label="清理范围" id="cache-scope">
            <NativeSelect
              id="cache-scope"
              value={scope}
              disabled={busy}
              onChange={(e) => setScope(e.target.value)}
            >
              <NativeSelectOption value="account">当前账号</NativeSelectOption>
              <NativeSelectOption value="legacy">
                旧公共截图目录
              </NativeSelectOption>
            </NativeSelect>
          </FormRow>
        </FieldGroup>
      )}
      {busy && <p role="status">正在处理…</p>}
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {report && <Report report={report} cleaned={cleaned} />}
      <div className="form-footer">
        <Button
          disabled={busy}
          onClick={() => void run(false)}
          variant="outline"
        >
          盘点缓存
        </Button>
        <Button
          disabled={
            busy || !report?.categories.some((c) => c.cleanableFiles > 0)
          }
          title={
            !report
              ? "请先盘点缓存"
              : report.categories.some((c) => c.cleanableFiles > 0)
                ? undefined
                : "没有可清理的缓存"
          }
          onClick={() => void run(true)}
        >
          清理缓存
        </Button>
      </div>
    </SectionCard>
  );
}
export function NodeCacheMaintenance({
  node,
  connected,
  acks,
  send,
}: {
  node: Device;
  connected: boolean;
  acks: Ack[];
  send: (m: Record<string, unknown>) => string;
}) {
  const [request, setRequest] = useState("");
  const ack = acks.find((a) => a.requestId === request),
    busy = ack?.phase === "pending" || ack?.phase === "forwarded";
  const report =
    ack?.phase === "completed" && ack.success ? ack.cache : undefined;
  if (!node.capabilities.includes("cache-maintenance")) return null;
  return (
    <SectionCard title="节点缓存">
      <div className="actions">
        <Button
          disabled={!connected || !node.online || busy}
          onClick={() =>
            setRequest(
              send({
                type: "command",
                targetDeviceId: node.deviceId,
                command: "cache-inspect",
              }),
            )
          }
          variant="outline"
        >
          盘点缓存
        </Button>
        <Button
          disabled={
            !connected ||
            !node.online ||
            busy ||
            !report?.categories.some((c) => c.cleanableFiles > 0)
          }
          onClick={() =>
            setRequest(
              send({
                type: "command",
                targetDeviceId: node.deviceId,
                command: "cache-clean",
              }),
            )
          }
        >
          清理可回收缓存
        </Button>
      </div>
      {ack && (
        <Alert
          variant={
            ack.phase === "completed" && !ack.success
              ? "destructive"
              : "default"
          }
        >
          <AlertDescription>
            {ack.phase === "pending"
              ? "等待执行"
              : ack.phase === "forwarded"
                ? "已转发，等待节点执行"
                : ack.phase === "uncertain"
                  ? "结果待核实，请重新盘点"
                  : ack.reason || (ack.success ? "已完成" : "执行失败")}
          </AlertDescription>
        </Alert>
      )}
      {report && <Report report={report} />}
    </SectionCard>
  );
}
