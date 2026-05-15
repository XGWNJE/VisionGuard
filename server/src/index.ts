// ┌─────────────────────────────────────────────────────────┐
// │ index.ts  v4.1.0                                         │
// │ 角色：服务器入口 — 组装 HTTP + WebSocket 服务器           │
// │ 职责：加载配置 → 创建 Express app → 挂载路由 →           │
// │       创建 HTTP server → 附加 WS server → 启动监听       │
// └─────────────────────────────────────────────────────────┘

// 先加载 .env，确保后续 config 读取时环境变量已就绪
import './env';

import http from 'http';
import express from 'express';
import rateLimit from 'express-rate-limit';
import { WebSocketServer } from 'ws';
import { config, validateConfig } from './config';
import alertRouter from './routes/alert';
import alertsQueryRouter from './routes/alerts';
import { handleConnection, getConnectionCount } from './services/ConnectionManager';
import { cleanupExpiredAlerts } from './services/AlertStore';
import screenshotRouter from './routes/screenshot';
import updateRouter from './routes/update';
import { startCleanupTimer, cleanupScreenshots } from './services/ScreenshotCleanup';
import path from 'path';

// ── Express app ────────────────────────────────────────────

const app = express();
app.use(express.json());

// 全局速率限制：所有 API 路由 100 req/15min per IP
const apiLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 100,
  standardHeaders: true,
  legacyHeaders: false,
  message: { ok: false, error: 'too many requests' },
});
app.use('/api', apiLimiter);

// 健康检查 (无需鉴权，独立限速)
const healthLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 10,
  standardHeaders: true,
  legacyHeaders: false,
  message: { ok: false, error: 'too many requests' },
});
app.get('/health', healthLimiter, (_req, res) => {
  res.json({ ok: true, uptime: process.uptime() });
});

// 路由
app.use(alertRouter);
app.use(alertsQueryRouter);
app.use(screenshotRouter);
app.use(updateRouter);

// 更新包静态文件下载
app.use('/releases', express.static(path.resolve(__dirname, '..', 'data', 'releases')));

// ── HTTP + WebSocket 服务器 ────────────────────────────────

const server = http.createServer(app);

const wss = new WebSocketServer({ server, maxPayload: 2 * 1024 * 1024 });

wss.on('connection', (ws, req) => {
  if (getConnectionCount() >= config.maxWsConnections) {
    const ip = req.socket.remoteAddress ?? 'unknown';
    console.warn(`[ws] 连接数已达上限 ${config.maxWsConnections}，拒绝新连接 ← ${ip}`);
    ws.close(1013, 'server busy');
    return;
  }
  handleConnection(ws);
});
// ── 启动 ──────────────────────────────────────────────────

validateConfig();

// 启动报警记录 TTL 清理定时器（每 30 分钟）
setInterval(cleanupExpiredAlerts, 30 * 60 * 1000);
cleanupExpiredAlerts();

// 启动截图 TTL 清理定时器
cleanupScreenshots();
startCleanupTimer();

server.listen(config.port, () => {
  console.log(`[server] VisionGuard Server v4.1.0 已启动`);
  console.log(`[server] HTTP + WS 监听端口: ${config.port}`);
  console.log(`[server] 截图模式: 内嵌 Base64 自动推送 (无 HTTP 文件存储)`);
  console.log(`[server] 报警记录 TTL: ${config.alertTtlHours} 小时`);
  console.log(`[server] WS 最大连接数: ${config.maxWsConnections}`);
  console.log(`[server] 检测端幽灵阈值: ${config.deviceOfflineMs / 1000}s / 接收端: ${config.receiverGhostThresholdMs / 1000}s`);
});
