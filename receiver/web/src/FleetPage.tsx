import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field";
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
} from "@/components/ui/input-group";
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
import { ExternalLink, Search } from "lucide-react";
import { EmptyState } from "./ConsoleLayout";
import {
  formatTime,
  isCameraComponent,
  typeLabel,
  type AlarmTimeZone,
  type Device,
  type Stream,
} from "./protocol";

export function NodeDirectory({
  nodes,
  streams,
  eventCount,
  search,
  setSearch,
  typeFilter,
  setTypeFilter,
  onlineOnly,
  setOnlineOnly,
  openNode,
  openEvents,
  timeZone,
}: {
  nodes: Device[];
  streams: Stream[];
  eventCount: number;
  search: string;
  setSearch: (v: string) => void;
  typeFilter: string;
  setTypeFilter: (v: string) => void;
  onlineOnly: boolean;
  setOnlineOnly: (v: boolean) => void;
  openNode: (node: Device) => void;
  openEvents: () => void;
  timeZone: AlarmTimeZone;
}) {
  const visible = nodes.filter(
    (n) =>
      (!typeFilter || n.nodeType === typeFilter) &&
      (!onlineOnly || n.online) &&
      `${n.deviceName} ${n.deviceId}`
        .toLowerCase()
        .includes(search.trim().toLowerCase()),
  );
  return (
    <div className="page-stack">
      <Card>
        <CardContent>
          <div className="fleet-summary" aria-label="账号运行概览">
            <div>
              <small>在线 / 已登记</small>
              <strong>
                {nodes.filter((n) => n.online).length} / {nodes.length}
              </strong>
            </div>
            <div>
              <small>检测中</small>
              <strong>
                {
                  nodes.filter(
                    (n) =>
                      n.online &&
                      !isCameraComponent(n.component) &&
                      n.nodeType !== "notification" &&
                      n.isMonitoring,
                  ).length
                }
              </strong>
            </div>
            <div>
              <small>推流中</small>
              <strong>{streams.filter((s) => s.isStreaming).length}</strong>
            </div>
            <div>
              <small>最近事件</small>
              <Button variant="link" onClick={openEvents}>
                <strong>{eventCount}</strong>
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>
      <FieldGroup className="fleet-filters">
        <Field>
          <FieldLabel htmlFor="node-search">搜索节点</FieldLabel>
          <InputGroup>
            <InputGroupInput
              id="node-search"
              placeholder="名称或设备 ID"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
            <InputGroupAddon>
              <Search />
            </InputGroupAddon>
          </InputGroup>
        </Field>
        <Field>
          <FieldLabel htmlFor="node-type">节点类型</FieldLabel>
          <NativeSelect
            id="node-type"
            value={typeFilter}
            onChange={(e) => setTypeFilter(e.target.value)}
          >
            <NativeSelectOption value="">全部类型</NativeSelectOption>
            <NativeSelectOption value="visual">
              视觉类型（推理 / 相机）
            </NativeSelectOption>
            <NativeSelectOption value="sensor">传感器节点</NativeSelectOption>
            <NativeSelectOption value="notification">
              通知节点
            </NativeSelectOption>
          </NativeSelect>
        </Field>
        <Button
          aria-pressed={onlineOnly}
          onClick={() => setOnlineOnly(!onlineOnly)}
          variant={onlineOnly ? "secondary" : "outline"}
        >
          仅在线
        </Button>
      </FieldGroup>
      <div className="data-table">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>节点</TableHead>
              <TableHead>类型 / 平台</TableHead>
              <TableHead>运行状态</TableHead>
              <TableHead>来源</TableHead>
              <TableHead>最近响应</TableHead>
              <TableHead className="action-heading">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {visible.map((node) => (
              <TableRow key={node.deviceId}>
                <TableCell className="node-name-cell">
                  <strong>{node.deviceName}</strong>
                  <small>{node.deviceId}</small>
                </TableCell>
                <TableCell>
                  {typeLabel(node.nodeType, node.component)}
                  <small>{node.platform}</small>
                </TableCell>
                <TableCell>
                  <Badge variant="secondary">
                    {!node.online
                      ? "离线"
                      : isCameraComponent(node.component)
                        ? streams.some(
                            (s) =>
                              s.publisherDeviceId === node.deviceId &&
                              s.isStreaming,
                          )
                          ? "推流中"
                          : "未推流"
                        : node.nodeType === "notification"
                          ? "通知服务已连接"
                          : node.isMonitoring
                            ? "检测中"
                            : node.isReady
                              ? "已就绪"
                              : "未检测"}
                  </Badge>
                </TableCell>
                <TableCell>{node.sources.length || "—"}</TableCell>
                <TableCell>
                  {node.lastSeen
                    ? formatTime(node.lastSeen, timeZone)
                    : "未报告"}
                </TableCell>
                <TableCell>
                  <div className="table-actions">
                    <Button variant="outline" onClick={() => openNode(node)}>
                      {node.nodeType === "notification"
                        ? "通知管理"
                        : "打开节点"}
                    </Button>
                  </div>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {!visible.length && (
          <EmptyState
            text={nodes.length ? "没有匹配的节点" : "尚无已登记节点"}
          />
        )}
      </div>
      <div className="actions">
        <Button asChild variant="outline">
          <a href="/camera/" target="_blank" rel="noopener noreferrer">
            浏览器相机
            <ExternalLink data-icon="inline-end" />
          </a>
        </Button>
      </div>
    </div>
  );
}
