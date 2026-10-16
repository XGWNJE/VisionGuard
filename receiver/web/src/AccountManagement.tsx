import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
  NativeSelect,
  NativeSelectOption,
} from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useEffect, useState } from "react";
import { EmptyState, FormRow, LongPage, SectionCard } from "./ConsoleLayout";
import { accountRequest, type Login } from "./account";
type ManagedAccount = {
  accountId: string;
  username: string;
  isAdmin: boolean;
  enabled: boolean;
};
export function AccountManagement({ login }: { login: Login }) {
  const [accounts, setAccounts] = useState<ManagedAccount[]>([]);
  const [username, setUsername] = useState(""),
    [password, setPassword] = useState("");
  const [administrator, setAdministrator] = useState(false);
  const [editing, setEditing] = useState<ManagedAccount | null>(null),
    [resetPassword, setResetPassword] = useState("");
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [message, setMessage] = useState("");
  const [loading, setLoading] = useState(true);
  const [feedbackTarget, setFeedbackTarget] = useState<
    "list" | "create" | "edit"
  >("list");
  async function refresh() {
    setLoading(true);
    try {
      const result = await accountRequest<{
        accounts: ManagedAccount[];
      }>("/api/admin/accounts", login.token);
      setAccounts(result.accounts);
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    let active = true;
    setLoading(true);
    void accountRequest<{
      accounts: ManagedAccount[];
    }>("/api/admin/accounts", login.token)
      .then((result) => {
        if (active) setAccounts(result.accounts);
      })
      .catch((e) => {
        if (active) setError(e.message);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [login.token]);
  async function create() {
    setFeedbackTarget("create");
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await accountRequest("/api/admin/accounts", login.token, {
        username: username.trim(),
        password,
        isAdmin: administrator,
      });
      setUsername("");
      setPassword("");
      setAdministrator(false);
      await refresh();
      setMessage("账号已创建");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function save() {
    setFeedbackTarget("edit");
    if (!editing) return;
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await accountRequest(
        `/api/admin/accounts/${encodeURIComponent(editing.accountId)}`,
        login.token,
        {
          enabled: editing.enabled,
          isAdmin: editing.isAdmin,
          ...(resetPassword ? { password: resetPassword } : {}),
        },
        "PATCH",
      );
      setResetPassword("");
      setEditing(null);
      await refresh();
      setMessage("账号已更新");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  const feedback = (target: typeof feedbackTarget) =>
    target === feedbackTarget ? (
      <>
        {error && (
          <Alert variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}
        {message && <p role="status">{message}</p>}
      </>
    ) : null;
  const selectAccount = (account: ManagedAccount | null) => {
    setEditing(account ? { ...account } : null);
    setResetPassword("");
    setError("");
    setMessage("");
  };
  return (
    <LongPage
      sections={[
        {
          id: "accounts-list",
          label: "账号列表",
          content: (
            <SectionCard
              title="账号列表"
              action={
                <Button
                  variant="ghost"
                  disabled={loading || busy}
                  onClick={() => {
                    setFeedbackTarget("list");
                    setError("");
                    void refresh().catch((e) => setError(e.message));
                  }}
                >
                  刷新
                </Button>
              }
            >
              {feedback("list")}
              {loading ? (
                <div role="status" aria-label="正在读取账号">
                  <Skeleton className="h-32 w-full" />
                </div>
              ) : (
                <div className="data-table">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>账号</TableHead>
                        <TableHead>权限</TableHead>
                        <TableHead>状态</TableHead>
                        <TableHead className="action-heading">操作</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {accounts.map((account) => (
                        <TableRow
                          key={account.accountId}
                          data-state={
                            editing?.accountId === account.accountId
                              ? "selected"
                              : undefined
                          }
                        >
                          <TableCell className="account-name-cell">
                            {account.username}
                            {account.accountId === login.account.accountId && (
                              <small>当前账号</small>
                            )}
                          </TableCell>
                          <TableCell>
                            {account.isAdmin ? "管理员" : "普通账号"}
                          </TableCell>
                          <TableCell>
                            <Badge variant="secondary">
                              {account.enabled ? "已启用" : "已禁用"}
                            </Badge>
                          </TableCell>
                          <TableCell>
                            <div className="table-actions">
                              <Button
                                variant="outline"
                                disabled={busy}
                                onClick={() => {
                                  selectAccount(account);
                                  document
                                    .getElementById("account-edit")
                                    ?.scrollIntoView({
                                      block: "start",
                                      behavior: "smooth",
                                    });
                                }}
                              >
                                定位设置
                              </Button>
                            </div>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                  {!accounts.length && <EmptyState text="暂无账号" />}
                </div>
              )}
            </SectionCard>
          ),
        },
        {
          id: "account-create",
          label: "创建账号",
          content: (
            <SectionCard title="创建账号">
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  void create();
                }}
              >
                <FieldGroup>
                  <FormRow label="账号" id="new-account-name">
                    <Input
                      id="new-account-name"
                      required
                      minLength={3}
                      maxLength={64}
                      pattern="[A-Za-z0-9][A-Za-z0-9._\-]{2,63}"
                      autoComplete="off"
                      value={username}
                      onChange={(e) => setUsername(e.target.value)}
                      disabled={busy}
                    />
                  </FormRow>
                  <FormRow label="初始密码" id="new-account-password">
                    <Input
                      id="new-account-password"
                      required
                      type="password"
                      minLength={8}
                      maxLength={256}
                      autoComplete="new-password"
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      disabled={busy}
                    />
                  </FormRow>
                  <Field orientation="horizontal">
                    <Checkbox
                      id="new-account-admin"
                      checked={administrator}
                      disabled={busy}
                      onCheckedChange={(checked) =>
                        setAdministrator(checked === true)
                      }
                    />
                    <FieldLabel htmlFor="new-account-admin">
                      管理员权限
                    </FieldLabel>
                  </Field>
                  <div className="form-footer">
                    <Button disabled={busy}>
                      {busy && feedbackTarget === "create"
                        ? "提交中…"
                        : "创建账号"}
                    </Button>
                  </div>
                  {feedback("create")}
                </FieldGroup>
              </form>
            </SectionCard>
          ),
        },
        {
          id: "account-edit",
          label: "账号设置",
          content: (
            <SectionCard title="账号设置">
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  void save();
                }}
              >
                <FieldGroup>
                  <FormRow label="当前账号" id="managed-account">
                    <NativeSelect
                      id="managed-account"
                      value={editing?.accountId ?? ""}
                      disabled={busy}
                      onChange={(e) =>
                        selectAccount(
                          accounts.find(
                            (a) => a.accountId === e.target.value,
                          ) ?? null,
                        )
                      }
                    >
                      <NativeSelectOption value="">选择账号</NativeSelectOption>
                      {accounts.map((account) => (
                        <NativeSelectOption
                          key={account.accountId}
                          value={account.accountId}
                        >
                          {account.username}
                        </NativeSelectOption>
                      ))}
                    </NativeSelect>
                  </FormRow>
                  <Field orientation="horizontal">
                    <Checkbox
                      id="managed-enabled"
                      checked={editing?.enabled ?? false}
                      disabled={busy || !editing}
                      onCheckedChange={(checked) =>
                        editing &&
                        setEditing({ ...editing, enabled: checked === true })
                      }
                    />
                    <FieldLabel htmlFor="managed-enabled">启用账号</FieldLabel>
                  </Field>
                  <Field orientation="horizontal">
                    <Checkbox
                      id="managed-admin"
                      checked={editing?.isAdmin ?? false}
                      disabled={busy || !editing}
                      onCheckedChange={(checked) =>
                        editing &&
                        setEditing({ ...editing, isAdmin: checked === true })
                      }
                    />
                    <FieldLabel htmlFor="managed-admin">管理员权限</FieldLabel>
                  </Field>
                  <FormRow label="重置密码" id="managed-password">
                    <Input
                      id="managed-password"
                      type="password"
                      minLength={8}
                      maxLength={256}
                      autoComplete="new-password"
                      placeholder="留空保留现有密码"
                      value={resetPassword}
                      onChange={(e) => setResetPassword(e.target.value)}
                      disabled={busy || !editing}
                    />
                  </FormRow>
                  <small>保存后撤销该账号现有会话</small>
                  <div className="form-footer">
                    <Button disabled={busy || !editing}>
                      {busy && feedbackTarget === "edit"
                        ? "保存中…"
                        : "保存账号"}
                    </Button>
                  </div>
                  {feedback("edit")}
                </FieldGroup>
              </form>
            </SectionCard>
          ),
        },
      ]}
    />
  );
}
