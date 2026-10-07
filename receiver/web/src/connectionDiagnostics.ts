export type ConnectionPhase = 'connecting' | 'authenticating' | 'authenticated';
type ConnectionEvent = 'connect-start' | 'socket-open' | 'auth-sent' | 'auth-success' | 'auth-rejected'
  | 'timeout' | 'socket-error' | 'socket-close' | 'disconnect' | 'retry-scheduled' | 'connect-error';
type ConnectionReason = 'connect-timeout' | 'auth-timeout' | 'service-timeout' | 'invalid-session'
  | 'server-rejected' | 'session-revoked' | 'transport-error' | 'transport-close' | 'history-unauthorized'
  | 'session-rotation' | 'effect-cleanup' | 'local-close' | 'invalid-url';
type ConnectionEntry = {
  attemptId: string; attempt: number; phase: ConnectionPhase; event: ConnectionEvent; elapsedMs: number;
  openElapsedMs?: number; readyState?: number; code?: number; wasClean?: boolean;
  reason?: ConnectionReason; retryInMs?: number;
};
const storageKey = 'visionguard.connection-log';
const maxEntries = 100, maxAgeMs = 24 * 60 * 60 * 1000;
const fields = ['attemptId','attempt','phase','event','elapsedMs','openElapsedMs','readyState','code','wasClean','reason','retryInMs'] as const;

/** Diagnostic metadata only: never pass messages, credentials, account identities or URLs. */
export function recordConnectionEvent(entry: ConnectionEntry): void {
  const timestamp = new Date().toISOString();
  const safe = Object.fromEntries(fields.filter(key => entry[key] !== undefined).map(key => [key,entry[key]]));
  const record = { timestamp, ...safe };
  console.info('[RelayAuth]', JSON.stringify(record));
  // This tab's history survives reload, but has no account/session data and stays bounded.
  try {
    const stored = sessionStorage.getItem(storageKey);
    const parsed: unknown = stored && stored.length <= 100_000 ? JSON.parse(stored) : [];
    const recent = Array.isArray(parsed) ? parsed.slice(-maxEntries).filter(item =>
      item && typeof item.timestamp === 'string' && Date.now() - Date.parse(item.timestamp) >= 0
      && Date.now() - Date.parse(item.timestamp) < maxAgeMs).map(item =>
      Object.fromEntries(['timestamp', ...fields].filter(key => Object.hasOwn(item,key)).map(key => [key,item[key]]))) : [];
    sessionStorage.setItem(storageKey, JSON.stringify([...recent, record].slice(-maxEntries)));
  } catch { /* Storage denial or corruption must never interrupt authentication. */ }
}
