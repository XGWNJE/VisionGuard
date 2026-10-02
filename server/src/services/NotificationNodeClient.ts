import WebSocket from 'ws';
import { NotificationSession } from './NotificationSession';
import type { WsAlertPush } from '../models/types';

/** Minimal notification-node transport; no Vigil integration or playback policy here. */
export class NotificationNodeClient {
  private ws?: WebSocket;
  private authenticated = false;
  private readonly timer: NodeJS.Timeout;
  private readonly session: NotificationSession;
  private stopped = false;

  constructor(
    private readonly url: string,
    private readonly auth: { token: string },
    receive: (event: WsAlertPush) => Promise<void>,
    connectionInterrupted: () => void,
  ) {
    this.session = new NotificationSession(message => this.send(message), receive, () => {
      this.ws?.terminate();
      connectionInterrupted();
    });
    this.connect();
    this.timer = setInterval(() => {
      this.session.tick();
      if (!this.ws || this.ws.readyState === WebSocket.CLOSED) this.connect();
      if (this.authenticated) this.send({ type: 'heartbeat-notifier' });
    }, 3000);
    this.timer.unref();
  }

  private connect(): void {
    if (this.stopped) return;
    const ws = new WebSocket(this.url, { handshakeTimeout: 5000 });
    this.ws = ws;
    ws.on('open', () => this.send({ type: 'auth', ...this.auth }));
    ws.on('message', raw => {
      if (this.ws !== ws || this.stopped) return;
      let message: any;
      try { message = JSON.parse(raw.toString()); } catch { return; }
      if (!message || typeof message !== 'object' || Array.isArray(message)) return;
      if (message.type === 'auth-result') {
        this.authenticated = message.success === true;
        if (this.authenticated) this.session.serviceResponded();
        else ws.close();
      } else if (this.authenticated && message.type === 'heartbeat-ack') this.session.serviceResponded();
      else if (this.authenticated && message.type === 'alert') void this.session.event(message);
      else if (message.type === 'kicked') ws.close();
    });
    ws.on('close', () => { if (this.ws === ws) this.authenticated = false; });
    ws.on('error', () => ws.terminate());
  }

  private send(message: object): void {
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(message));
  }

  stop(): void {
    this.stopped = true;
    clearInterval(this.timer);
    this.session.stop();
    this.ws?.terminate();
  }
}
