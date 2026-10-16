import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
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
import { Search, X } from "lucide-react";
import type { Login } from "./account";
import { EmptyState, SectionCard } from "./ConsoleLayout";
import { EventImage } from "./EventImage";
import {
  eventLabel,
  formatTime,
  timeStandardLabel,
  type AlarmTimeZone,
  type Alert as EventAlert,
} from "./protocol";

type Props = {
  alerts: EventAlert[];
  allAlerts: EventAlert[];
  selected: EventAlert | null;
  onSelect: (alert: EventAlert) => void;
  login: Login;
  timeZone: AlarmTimeZone;
  receipts: Record<string, string[]>;
  onNode: (deviceId: string) => void;
  canOpenNode: (deviceId: string) => boolean;
  search: string;
  setSearch: (value: string) => void;
  kind: string;
  setKind: (value: string) => void;
  start: string;
  setStart: (value: string) => void;
  end: string;
  setEnd: (value: string) => void;
  timeError: string;
  nodeFilter: string;
  clearNodeFilter: () => void;
};

export function EventWorkspace({
  alerts,
  allAlerts,
  selected,
  onSelect,
  login,
  timeZone,
  receipts,
  onNode,
  canOpenNode,
  search,
  setSearch,
  kind,
  setKind,
  start,
  setStart,
  end,
  setEnd,
  timeError,
  nodeFilter,
  clearNodeFilter,
}: Props) {
  const active =
    alerts.find((a) => a.alertId === selected?.alertId) ?? alerts[0];
  return (
    <div className="page-stack">
      {nodeFilter && (
        <div className="actions">
          <Button
            variant="secondary"
            onClick={clearNodeFilter}
            aria-label="清除节点筛选"
          >
            {nodeFilter}
            <X data-icon="inline-end" />
          </Button>
        </div>
      )}
      <FieldGroup className="event-filters">
        <Field>
          <FieldLabel htmlFor="event-search">搜索事件</FieldLabel>
          <InputGroup>
            <InputGroupInput
              id="event-search"
              placeholder="节点、来源或摘要"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
            <InputGroupAddon>
              <Search />
            </InputGroupAddon>
          </InputGroup>
        </Field>
        <Field>
          <FieldLabel htmlFor="event-kind">类型</FieldLabel>
          <NativeSelect
            id="event-kind"
            value={kind}
            onChange={(e) => setKind(e.target.value)}
          >
            <NativeSelectOption value="">全部事件</NativeSelectOption>
            {[...new Set(allAlerts.map((a) => a.eventKind))].map((value) => (
              <NativeSelectOption key={value} value={value}>
                {eventLabel(value)}
              </NativeSelectOption>
            ))}
          </NativeSelect>
        </Field>
        <Field data-invalid={!!timeError}>
          <FieldLabel htmlFor="event-start">开始时间</FieldLabel>
          <Input
            id="event-start"
            type="datetime-local"
            step="1"
            value={start}
            onChange={(e) => setStart(e.target.value)}
            aria-invalid={!!timeError}
            aria-describedby={timeError ? "event-time-error" : undefined}
          />
        </Field>
        <Field data-invalid={!!timeError}>
          <FieldLabel htmlFor="event-end">结束时间</FieldLabel>
          <Input
            id="event-end"
            type="datetime-local"
            step="1"
            value={end}
            onChange={(e) => setEnd(e.target.value)}
            aria-invalid={!!timeError}
            aria-describedby={timeError ? "event-time-error" : undefined}
          />
        </Field>
      </FieldGroup>
      {(start || end) && (
        <div className="actions">
          <Button
            variant="ghost"
            onClick={() => {
              setStart("");
              setEnd("");
            }}
          >
            清除时间
          </Button>
        </div>
      )}
      {timeError && (
        <Alert variant="destructive" id="event-time-error">
          <AlertDescription>{timeError}</AlertDescription>
        </Alert>
      )}
      <div className="event-layout">
        <div className="data-table">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>节点 / 来源</TableHead>
                <TableHead>事件类型</TableHead>
                <TableHead>时间 / 回执</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {alerts.map((alert) => (
                <TableRow
                  key={alert.alertId}
                  data-state={
                    active?.alertId === alert.alertId ? "selected" : undefined
                  }
                  onClick={() => onSelect(alert)}
                >
                  <TableCell className="event-name-cell">
                    <Button
                      variant="ghost"
                      className="event-select"
                      aria-pressed={active?.alertId === alert.alertId}
                    >
                      <span>
                        {alert.deviceName || alert.deviceId}
                        <small>
                          {alert.sourceName || alert.sourceId || "节点整体"}
                        </small>
                      </span>
                    </Button>
                  </TableCell>
                  <TableCell>
                    <Badge variant="secondary">
                      {eventLabel(alert.eventKind)}
                    </Badge>
                  </TableCell>
                  <TableCell className="event-time-cell">
                    <time>{formatTime(alert.timestamp, timeZone)}</time>
                    <small>
                      {(receipts[alert.alertId] ?? []).length
                        ? `${receipts[alert.alertId].length} 个通知节点已收件`
                        : "暂无回执"}
                    </small>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {!alerts.length && !timeError && (
            <EmptyState
              text={allAlerts.length ? "没有匹配的事件" : "暂无事件"}
            />
          )}
        </div>
        <SectionCard title="事件详情">
          {active ? (
            <>
              <div className="event-image-frame">
                <EventImage alert={active} login={login} />
              </div>
              <dl>
                <dt>节点</dt>
                <dd>{active.deviceName || active.deviceId}</dd>
                {active.sourceId && (
                  <>
                    <dt>来源</dt>
                    <dd>{active.sourceName || active.sourceId}</dd>
                  </>
                )}
                <dt>时间</dt>
                <dd>{formatTime(active.timestamp, timeZone)}</dd>
                <dt>时间标准</dt>
                <dd>{timeStandardLabel(timeZone)}</dd>
                <dt>摘要</dt>
                <dd>{active.summary || "无摘要"}</dd>
                <dt>收件回执</dt>
                <dd>
                  {receipts[active.alertId]?.length
                    ? receipts[active.alertId].join("、")
                    : "暂无回执"}
                </dd>
              </dl>
              {!!active.detections?.length && (
                <>
                  <h3>检测结果</h3>
                  <div className="data-table">
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>类别</TableHead>
                          <TableHead>置信度</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {active.detections.map((d, index) => (
                          <TableRow key={index}>
                            <TableCell>{d.label}</TableCell>
                            <TableCell>
                              {Math.round(d.confidence * 100)}%
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </div>
                </>
              )}
              <div className="actions">
                <Button
                  variant="outline"
                  disabled={!canOpenNode(active.deviceId)}
                  onClick={() => onNode(active.deviceId)}
                >
                  打开节点
                </Button>
              </div>
            </>
          ) : (
            <EmptyState text="未选择事件" />
          )}
        </SectionCard>
      </div>
    </div>
  );
}
