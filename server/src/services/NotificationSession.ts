import { performance } from 'node:perf_hooks';
import type { WsAlertPush } from '../models/types';

/** Reusable notifier session logic. Transport and sound delivery belong to the actual notification node. */
export class NotificationSession {
  private lastServiceResponse: number;
  private interrupted = false;
  private stopped = false;
  private readonly accepted = new Map<string, number>();
  private readonly processing = new Set<string>();

  constructor(
    private readonly send: (message: object) => void,
    private readonly receive: (event: WsAlertPush) => Promise<void>,
    private readonly connectionInterrupted: () => void,
    private readonly monotonicNow: () => number = () => performance.now(),
    private readonly wallNow: () => number = () => Date.now(),
    private readonly timeoutMs = 45_000,
  ) { this.lastServiceResponse = monotonicNow(); }

  serviceResponded(): void {
    if (this.stopped) return;
    this.lastServiceResponse = this.monotonicNow();
    this.interrupted = false;
  }

  // Reconnecting or sending a heartbeat must not reset the service-response deadline.
  tick(): void {
    if (this.stopped) return;
    if (!this.interrupted && this.monotonicNow() - this.lastServiceResponse >= this.timeoutMs) {
      this.interrupted = true;
      this.connectionInterrupted();
    }
    for (const [id, expires] of this.accepted) if (expires <= this.wallNow()) this.accepted.delete(id);
  }

  async event(event: WsAlertPush): Promise<void> {
    if (this.stopped || !event.alertId || !event.expiresAt || !Number.isFinite(Date.parse(event.expiresAt)) || Date.parse(event.expiresAt) <= this.wallNow()) return;
    if (this.processing.has(event.alertId)) return;
    if (!this.accepted.has(event.alertId)) {
      this.processing.add(event.alertId);
      try {
        await this.receive(event);
        if (this.stopped) return;
        this.accepted.set(event.alertId, Date.parse(event.expiresAt));
      } catch { return; }
      finally { this.processing.delete(event.alertId); }
    }
    this.send({ type: 'notification-receipt', alertId: event.alertId });
  }

  stop(): void { this.stopped = true; this.accepted.clear(); }
}
