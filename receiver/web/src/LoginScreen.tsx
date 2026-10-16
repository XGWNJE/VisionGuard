import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { useEffect, useState } from "react";
import { accountRequest, type Login } from "./account";
import { performConsoleLogin } from "./consoleLogin";
import { websocketURL } from "./protocol";
import {
  clearRememberedLogin,
  readRememberedLogin,
  saveRememberedLogin,
} from "./rememberedLogin";
import { clearSessionLogin } from "./sessionLogin";
export function LoginScreen({
  onLogin,
  error,
}: {
  onLogin: (value: Login, warning?: string) => void;
  error: string;
}) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState("");
  const [remember, setRemember] = useState(false),
    [restoring, setRestoring] = useState(true);
  useEffect(() => {
    let active = true;
    void readRememberedLogin()
      .then((saved) => {
        if (active && saved) {
          setUsername(saved.username);
          setPassword(saved.password);
          setRemember(true);
        }
      })
      .catch(() => {
        if (active) setFailure("无法读取记住的账号密码，可手动填写后登录");
      })
      .finally(() => {
        if (active) setRestoring(false);
      });
    return () => {
      active = false;
    };
  }, []);
  async function changeRemember(checked: boolean) {
    if (checked) {
      setRemember(true);
      return;
    }
    setBusy(true);
    setFailure("");
    try {
      await clearRememberedLogin();
      setRemember(false);
    } catch {
      setFailure("无法清除已记住的账号密码，请检查浏览器站点存储权限");
    } finally {
      setBusy(false);
    }
  }
  async function submit() {
    setBusy(true);
    setFailure("");
    try {
      websocketURL(location.origin);
      const { value, warning } = await performConsoleLogin(username, password);
      if (remember) {
        try {
          await saveRememberedLogin({
            username: value.account.username,
            password,
          });
        } catch {
          await accountRequest("/api/account/logout", value.token, {}).catch(
            () => {},
          );
          await clearSessionLogin().catch(() => {});
          throw new Error("账号密码未能保存，请取消“记住账号密码”后重试");
        }
      }
      onLogin(value, warning);
      setPassword("");
    } catch (e) {
      setFailure((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="login-page">
      <div className="login-layout">
        <header className="login-header">
          <strong>VisionGuard</strong>
        </header>
        <Card className="login-panel">
          <CardHeader>
            <CardTitle role="heading" aria-level={1}>
              登录控制台
            </CardTitle>
          </CardHeader>
          <CardContent>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                void submit();
              }}
            >
              <FieldGroup>
                {(failure || error) && (
                  <Alert variant="destructive" className="error">
                    <AlertDescription>{failure || error}</AlertDescription>
                  </Alert>
                )}
                <Field>
                  <FieldLabel>
                    账号
                    <Input
                      required
                      maxLength={64}
                      autoComplete="username"
                      value={username}
                      onChange={(e) => setUsername(e.target.value)}
                      disabled={busy || restoring}
                    />
                  </FieldLabel>
                </Field>
                <Field>
                  <FieldLabel>
                    密码
                    <Input
                      required
                      type="password"
                      maxLength={256}
                      autoComplete="current-password"
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      disabled={busy || restoring}
                    />
                  </FieldLabel>
                </Field>
                <Field>
                  <FieldLabel className="remember-login">
                    <Checkbox
                      checked={remember}
                      disabled={busy || restoring}
                      onCheckedChange={(checked) =>
                        void changeRemember(checked === true)
                      }
                    />
                    记住账号密码
                  </FieldLabel>
                </Field>
                <Button
                  type="submit"
                  disabled={busy || restoring}
                  variant="default"
                >
                  {busy ? "登录中…" : "登录"}
                </Button>
              </FieldGroup>
            </form>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
