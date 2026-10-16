import { ThemeProvider } from "@/components/theme-provider";
import { Alert, AlertDescription } from "@/components/ui/alert";
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb";
import { Button } from "@/components/ui/button";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
} from "@/components/ui/sidebar";
import { Skeleton } from "@/components/ui/skeleton";
import { TooltipProvider } from "@/components/ui/tooltip";
import { RefreshCw } from "lucide-react";
import React, { useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { accountRequest, AccountRequestError, type Login } from "./account";
import { AccountManagement } from "./AccountManagement";
import { AppearanceSelector, useAppearance } from "./appearance";
import { performConsoleLogin } from "./consoleLogin";
import { DraftNavigation, useDraftNavigation } from "./DraftNavigation";
import { EventWorkspace } from "./EventWorkspace";
import { NodeDirectory } from "./FleetPage";
import { requestLandscape } from "./landscape";
import { LoginScreen } from "./LoginScreen";
import { NodeWorkspace } from "./NodeWorkspace";
import { NotificationWorkspace } from "./NotificationWorkspace";
import { type ParameterDrafts } from "./Parameters";
import {
  bootstrapProtocolVersion,
  debugLoginScreen,
  previewCredentials,
} from "./previewSignIn";
import {
  isWithinEventTime,
  mergeDevices,
  parseEventTime,
  timeStandardLabel,
  typeLabel,
  type Device,
  type Alert as EventAlert,
} from "./protocol";
import { setProtocolVersion } from "./session.ts";
import {
  clearSessionLogin,
  restoreSessionLogin,
  saveSessionLogin,
} from "./sessionLogin";
import { SettingsPage } from "./SettingsPage";
import "./style.css";
import { useRelay } from "./useRelay";
type Page = "节点" | "事件" | "通知范围" | "设置" | "账号管理";
function App() {
  useEffect(() => {
    requestLandscape();
    document.addEventListener("pointerdown", requestLandscape);
    document.addEventListener("fullscreenchange", requestLandscape);
    return () => {
      document.removeEventListener("pointerdown", requestLandscape);
      document.removeEventListener("fullscreenchange", requestLandscape);
    };
  }, []);
  const navigate = useDraftNavigation();
  const appearance = useAppearance();
  const parameterDrafts = useRef<ParameterDrafts>(new Map());
  const [login, setLogin] = useState<Login | null>(null);
  const loginRef = useRef<Login | null>(null);
  const [restoringSession, setRestoringSession] = useState(true);
  const [sessionWarning, setSessionWarning] = useState("");
  function updateLogin(value: Login | null) {
    loginRef.current = value;
    setLogin(value);
    setProtocolVersion(value?.version);
  }
  const [loginError, setLoginError] = useState("");
  const relay = useRelay(login);
  const [page, setPage] = useState<Page>("节点");
  const [selected, setSelected] = useState("");
  const [event, setEvent] = useState<EventAlert | null>(null);
  const [search, setSearch] = useState("");
  const [typeFilter, setTypeFilter] = useState("");
  const [onlineOnly, setOnlineOnly] = useState(false);
  const [eventSearch, setEventSearch] = useState("");
  const [eventNodeId, setEventNodeId] = useState("");
  const [eventKind, setEventKind] = useState("");
  const [eventStart, setEventStart] = useState("");
  const [eventEnd, setEventEnd] = useState("");
  const [scopeSelected, setScopeSelected] = useState("");
  function clearSession(error = "") {
    void clearSessionLogin().catch(() =>
      setLoginError("无法清除保存的登录会话，请检查浏览器站点存储权限"),
    );
    parameterDrafts.current.clear();
    updateLogin(null);
    setLoginError(error);
    setEvent(null);
    setSelected("");
    setSearch("");
    setTypeFilter("");
    setOnlineOnly(false);
    setEventSearch("");
    setEventNodeId("");
    setEventKind("");
    setEventStart("");
    setEventEnd("");
    setScopeSelected("");
    setPage("节点");
  }
  useEffect(() => {
    if (relay.authExpired && loginRef.current?.token === login?.token)
      clearSession("登录已失效，请重新登录");
  }, [relay.authExpired, login?.token]);
  useEffect(() => {
    let active = true;
    const abort = new AbortController(),
      timer = setTimeout(() => abort.abort(), 10000);
    void (async () => {
      // 先把协议版本就位（取自 /health），否则部署实例上的登录会被 426 挡掉。
      await bootstrapProtocolVersion();
      let restored: Login | null = null;
      try {
        restored = await restoreSessionLogin(abort.signal);
      } catch {
        if (active) setLoginError("无法恢复登录会话，请检查浏览器站点存储权限");
      }
      if (!active) return;
      if (restored && Date.parse(restored.expiresAt) > Date.now()) {
        updateLogin(restored);
        return;
      }
      if (debugLoginScreen()) return; // ?login：停在登录界面调登录流程
      const preview = await previewCredentials();
      if (!active || !preview) return;
      try {
        const { value, warning } = await performConsoleLogin(
          preview.username,
          preview.password,
        );
        if (!active) return;
        updateLogin(value);
        setSessionWarning(warning);
        setPage("节点");
      } catch (error) {
        if (active)
          setLoginError(`内置预览登录失败：${(error as Error).message}`);
      }
    })().finally(() => {
      clearTimeout(timer);
      if (active) setRestoringSession(false);
    });
    return () => {
      active = false;
      clearTimeout(timer);
      abort.abort();
    };
  }, []);
  useEffect(() => {
    if (!login) return;
    const expire = () => {
      if (
        loginRef.current?.token === login.token &&
        Date.parse(login.expiresAt) <= Date.now()
      ) {
        relay.suspend();
        clearSession("登录已满 24 小时，请重新登录");
        void accountRequest("/api/account/logout", login.token, {}).catch(
          () => {},
        );
      }
    };
    const timer = setTimeout(
      expire,
      Math.max(0, Date.parse(login.expiresAt) - Date.now()),
    );
    window.addEventListener("focus", expire);
    document.addEventListener("visibilitychange", expire);
    return () => {
      clearTimeout(timer);
      window.removeEventListener("focus", expire);
      document.removeEventListener("visibilitychange", expire);
    };
  }, [login]);
  async function logout() {
    const current = loginRef.current;
    relay.suspend();
    clearSession();
    try {
      if (current)
        await accountRequest("/api/account/logout", current.token, {});
    } catch (error) {
      if (
        !loginRef.current &&
        !(error instanceof AccountRequestError && error.status === 401)
      )
        setLoginError("已退出本地会话，服务暂不可达");
    }
  }
  const timeZone = relay.timeStandard?.timeZone ?? "Asia/Shanghai";
  useEffect(() => {
    setEventStart("");
    setEventEnd("");
  }, [timeZone]);
  useEffect(() => {
    window.scrollTo({ top: 0, behavior: "instant" });
  }, [page, selected]);
  const nodes = useMemo(
    () =>
      mergeDevices(relay.devices, [...relay.registered, ...relay.notifiers]),
    [relay.devices, relay.registered, relay.notifiers],
  );
  const node = nodes.find((d) => d.deviceId === selected);
  const rangeStart = parseEventTime(eventStart, timeZone),
    rangeEnd = parseEventTime(eventEnd, timeZone);
  const eventTimeError =
    Number.isNaN(rangeStart) || Number.isNaN(rangeEnd)
      ? "请输入有效时间"
      : rangeStart !== null && rangeEnd !== null && rangeStart > rangeEnd
        ? "结束时间不能早于开始时间"
        : "";
  const filteredEvents = relay.alerts.filter(
    (a) =>
      !eventTimeError &&
      isWithinEventTime(a.timestamp, rangeStart, rangeEnd) &&
      (!eventKind || a.eventKind === eventKind) &&
      (!eventNodeId || a.deviceId === eventNodeId) &&
      `${a.deviceName} ${a.sourceName} ${a.summary}`
        .toLowerCase()
        .includes(eventSearch.trim().toLowerCase()),
  );
  if (restoringSession)
    return (
      <div role="status" aria-label="正在恢复登录" className="login-page">
        <Skeleton className="h-8 w-64" />
      </div>
    );
  if (!login)
    return (
      <>
        <div className="login-appearance">
          <AppearanceSelector preference={appearance} showIcons={false} />
        </div>
        <LoginScreen
          error={loginError}
          onLogin={(value, warning) => {
            updateLogin(value);
            setSessionWarning(warning ?? "");
            setLoginError("");
            setPage("节点");
            setEvent(null);
          }}
        />
      </>
    );

  const labels: Record<Page, string> = {
    节点: "节点总览",
    事件: "事件记录",
    通知范围: "通知管理",
    设置: "设置",
    账号管理: "账号管理",
  };
  const openNode = (target: Device) =>
    navigate(() => {
      if (target.nodeType === "notification") {
        setScopeSelected(target.deviceId);
        setPage("通知范围");
      } else {
        setSelected(target.deviceId);
        setPage("节点");
      }
    });
  return (
    <SidebarProvider
      className="console-shell"
      style={{ "--sidebar-width": "13rem" } as React.CSSProperties}
    >
      <Sidebar collapsible="none" className="console-sidebar">
        <SidebarHeader className="brand">
          <strong>VisionGuard</strong>
        </SidebarHeader>
        <SidebarContent>
          {(
            [
              { title: "工作区", items: ["节点", "事件", "通知范围"] },
              {
                title: "管理",
                items: login.account.isAdmin ? ["设置", "账号管理"] : ["设置"],
              },
            ] as { title: string; items: Page[] }[]
          ).map((group) => (
            <SidebarGroup key={group.title}>
              <SidebarGroupLabel>{group.title}</SidebarGroupLabel>
              <SidebarGroupContent>
                <SidebarMenu aria-label={group.title}>
                  {group.items.map((item) => (
                    <SidebarMenuItem key={item}>
                      <SidebarMenuButton
                        isActive={page === item}
                        aria-current={page === item ? "page" : undefined}
                        onClick={() =>
                          navigate(() => {
                            setPage(item);
                            if (item === "事件") setEventNodeId("");
                            if (item === "节点") setSelected("");
                          })
                        }
                      >
                        {labels[item]}
                      </SidebarMenuButton>
                    </SidebarMenuItem>
                  ))}
                </SidebarMenu>
              </SidebarGroupContent>
            </SidebarGroup>
          ))}
        </SidebarContent>
        <SidebarFooter>
          <span>{login.account.username}</span>
          <small>{login.account.isAdmin ? "管理员" : "普通账号"}</small>
        </SidebarFooter>
      </Sidebar>
      <main className="console-main">
        <header className="context-bar">
          <Breadcrumb>
            <BreadcrumbList>
              <BreadcrumbItem>
                {page === "节点" && node ? (
                  <BreadcrumbLink asChild>
                    <Button
                      variant="link"
                      onClick={() => navigate(() => setSelected(""))}
                    >
                      节点总览
                    </Button>
                  </BreadcrumbLink>
                ) : (
                  <BreadcrumbPage>{labels[page]}</BreadcrumbPage>
                )}
              </BreadcrumbItem>
              {page === "节点" && node && (
                <>
                  <BreadcrumbSeparator />
                  <BreadcrumbItem>
                    <BreadcrumbPage
                      className="truncate"
                      title={node.deviceName}
                    >
                      {node.deviceName}
                    </BreadcrumbPage>
                  </BreadcrumbItem>
                </>
              )}
            </BreadcrumbList>
          </Breadcrumb>
          <div className="toolbar">
            <span role="status" className="connection">
              <span className={relay.connected ? "dot online" : "dot"} />
              {relay.status}
            </span>
            <Button
              variant="ghost"
              size="icon"
              aria-label="刷新节点与事件"
              onClick={relay.refresh}
              disabled={!relay.connected}
            >
              <RefreshCw />
            </Button>
          </div>
        </header>
        <div
          className="console-scroll"
          key={page === "节点" ? selected || page : page}
        >
          <header className="page-heading">
            <div>
              <h1>
                {page === "节点" && node ? node.deviceName : labels[page]}
              </h1>
              {page === "事件" && (
                <small>最多 100 条 · {timeStandardLabel(timeZone)}</small>
              )}
              {page === "节点" && node && (
                <small>
                  {typeLabel(node.nodeType, node.component)} · {node.platform} ·{" "}
                  {node.online ? "在线" : "离线"}
                </small>
              )}
            </div>
          </header>
          {sessionWarning && (
            <div className="page-feedback">
              <Alert>
                <AlertDescription>{sessionWarning}</AlertDescription>
              </Alert>
            </div>
          )}
          {page === "节点" &&
            (node ? (
              <NodeWorkspace
                key={node.deviceId}
                node={node}
                nodes={nodes}
                streams={relay.streams}
                bindings={relay.bindings}
                login={login}
                connected={relay.connected}
                acks={relay.acks}
                send={relay.send}
                drafts={parameterDrafts.current}
                timeZone={timeZone}
                onChanged={relay.refresh}
                onEvents={() =>
                  navigate(() => {
                    setEventSearch("");
                    setEventNodeId(node.deviceId);
                    setEventKind("");
                    setEventStart("");
                    setEventEnd("");
                    setEvent(null);
                    setPage("事件");
                  })
                }
              />
            ) : (
              <NodeDirectory
                nodes={nodes}
                streams={relay.streams}
                eventCount={relay.alerts.length}
                search={search}
                setSearch={setSearch}
                typeFilter={typeFilter}
                setTypeFilter={setTypeFilter}
                onlineOnly={onlineOnly}
                setOnlineOnly={setOnlineOnly}
                openNode={openNode}
                openEvents={() =>
                  navigate(() => {
                    setEventNodeId("");
                    setPage("事件");
                  })
                }
                timeZone={timeZone}
              />
            ))}
          {page === "事件" && (
            <EventWorkspace
              alerts={filteredEvents}
              allAlerts={relay.alerts}
              selected={event}
              onSelect={setEvent}
              login={login}
              timeZone={timeZone}
              receipts={relay.receipts}
              onNode={(id) => {
                const target = nodes.find((n) => n.deviceId === id);
                if (target) openNode(target);
              }}
              canOpenNode={(id) => nodes.some((n) => n.deviceId === id)}
              search={eventSearch}
              setSearch={setEventSearch}
              kind={eventKind}
              setKind={setEventKind}
              start={eventStart}
              setStart={setEventStart}
              end={eventEnd}
              setEnd={setEventEnd}
              timeError={eventTimeError}
              nodeFilter={
                eventNodeId
                  ? nodes.find((n) => n.deviceId === eventNodeId)?.deviceName ||
                    eventNodeId
                  : ""
              }
              clearNodeFilter={() => setEventNodeId("")}
            />
          )}
          {page === "通知范围" && (
            <NotificationWorkspace
              selected={scopeSelected}
              onSelect={setScopeSelected}
              notifiers={relay.notifiers}
              nodes={nodes}
              login={login}
              connected={relay.connected}
              acks={relay.acks}
              send={relay.send}
              timeZone={timeZone}
              onChanged={relay.refresh}
            />
          )}
          {page === "设置" && (
            <SettingsPage
              login={login}
              standard={relay.timeStandard}
              connected={relay.connected}
              acks={relay.acks}
              send={relay.send}
              onDeviceNameChanged={(name) => {
                if (loginRef.current?.token === login.token) {
                  const renamed = {
                    ...login,
                    device: { ...login.device, deviceName: name },
                  };
                  updateLogin(renamed);
                  void saveSessionLogin(renamed).catch(() => {});
                }
              }}
              onLogout={() => void logout()}
              onPasswordChanged={(warning) => {
                if (loginRef.current?.token === login.token)
                  clearSession(warning || "密码已修改，请重新登录");
              }}
            />
          )}
          {page === "账号管理" && login.account.isAdmin && (
            <AccountManagement login={login} />
          )}
        </div>
      </main>
    </SidebarProvider>
  );
}
createRoot(document.getElementById("root")!).render(
  <ThemeProvider>
    <TooltipProvider>
      <DraftNavigation>
        <App />
      </DraftNavigation>
    </TooltipProvider>
  </ThemeProvider>,
);
