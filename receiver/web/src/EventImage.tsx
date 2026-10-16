import { Alert, AlertDescription } from "@/components/ui/alert";
import { Skeleton } from "@/components/ui/skeleton";
import React, { useState } from "react";
import type { Login } from "./account";
import type { Alert as EventAlert } from "./protocol";
import { versionHeaders } from "./session";
export function EventImage({
  alert,
  login,
}: {
  alert: EventAlert;
  login: Login;
}) {
  const [image, setImage] = useState<string | null>(null);
  const [imageError, setImageError] = useState("");
  React.useEffect(() => {
    setImage(null);
    setImageError("");
    if (!alert.hasScreenshot || !alert.screenshotUrl) return;
    let url: URL;
    try {
      url = new URL(alert.screenshotUrl, location.origin);
    } catch {
      setImageError("截图地址无效");
      return;
    }
    if (
      url.origin !== location.origin ||
      !url.pathname.startsWith("/screenshots/")
    ) {
      setImageError("截图地址无效");
      return;
    }
    const controller = new AbortController();
    let objectURL = "";
    void fetch(url, {
      headers: { ...versionHeaders(), Authorization: `Bearer ${login.token}` },
      signal: controller.signal,
      cache: "no-store",
    })
      .then(async (r) => {
        if (!r.ok) throw new Error();
        const blob = await r.blob();
        if (!blob.type.startsWith("image/")) throw new Error();
        if (!controller.signal.aborted) {
          objectURL = URL.createObjectURL(blob);
          setImage(objectURL);
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) setImageError("截图暂不可用");
      });
    return () => {
      controller.abort();
      if (objectURL) URL.revokeObjectURL(objectURL);
    };
  }, [alert.alertId, alert.hasScreenshot, alert.screenshotUrl, login.token]);
  return (
    <>
      {image && (
        <img
          src={image}
          alt="事件截图"
          onError={() => {
            setImage(null);
            setImageError("截图无法显示");
          }}
          className="event-image"
        />
      )}
      {alert.hasScreenshot && !image && !imageError && (
        <div role="status" aria-label="正在加载截图">
          <Skeleton className="h-44 w-full" />
        </div>
      )}
      {!alert.hasScreenshot && <p className="subtle">无截图</p>}
      {imageError && (
        <Alert variant="destructive" className="error">
          <AlertDescription>{imageError}</AlertDescription>
        </Alert>
      )}
    </>
  );
}
