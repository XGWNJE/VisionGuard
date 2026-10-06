// ┌─────────────────────────────────────────────────────────┐
// │ index.ts  v0.6.3                                         │
// │ 角色：服务器入口 — 组装 HTTP + WebSocket 服务器           │
// │ 职责：加载配置 → 创建 Express app → 挂载路由 →           │
// │       创建 HTTP server → 附加 WS server → 启动监听       │
// └─────────────────────────────────────────────────────────┘

// 先加载 .env，确保后续 config 读取时环境变量已就绪
import './env';

import http from 'http';
import https from 'node:https';
import fs from 'node:fs';
import express from 'express';
import rateLimit from 'express-rate-limit';
import { WebSocketServer } from 'ws';
import { config, validateConfig } from './config';
import alertRouter from './routes/alert';
import alertsQueryRouter from './routes/alerts';
import { handleConnection } from './services/ConnectionManager';
import { cleanupExpiredAlerts } from './services/AlertStore';
import screenshotRouter from './routes/screenshot';
import updateRouter from './routes/update';
import accountRouter from './routes/account';
import { createApiLimiter } from './middleware/rateLimit';
import { accountStore } from './services/AccountStore';
import streamsRouter from './routes/streams';
import { mediaRelay } from './services/MediaRelay';
import { websocketLimits } from './services/WebSocketLimits';
import { startCleanupTimer, cleanupScreenshots } from './services/ScreenshotCleanup';
import path from 'path';

// ── Express app ────────────────────────────────────────────

const app = express();
accountStore.ensureAdministrator('xgwnje');
app.set('trust proxy', 1);
app.use(express.json({ limit: '16kb' }));

// 已认证请求按账号、设备和组件限速；匿名请求保留 IP 限速。
app.use('/api', createApiLimiter());

// 健康检查 (无需鉴权，独立限速)
const healthLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 10,
  standardHeaders: true,
  legacyHeaders: false,
  message: { ok: false, error: 'too many requests' },
});
app.get('/health', healthLimiter, (_req, res) => {
  res.json({ ok: true, channel: config.channelId, uptime: process.uptime() });
});

// 路由
app.use(alertRouter);
app.use(alertsQueryRouter);
app.use(screenshotRouter);
app.use(updateRouter);
app.use(accountRouter);
app.use(streamsRouter);

// The console shares this service and origin; credentials stay in browser memory.
app.use('/console', express.static(path.resolve(__dirname, 'console'), {
  setHeaders: res => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
  },
}));

// 更新包静态文件下载
app.use('/releases', express.static(path.resolve(__dirname, '..', 'data', 'releases')));
// 模型文件静态服务
app.use('/models', express.static(path.resolve(__dirname, '..', 'data', 'models')));

// ── HTTP + WebSocket 服务器 ────────────────────────────────

const server = config.tlsCertFile && config.tlsKeyFile
  ? https.createServer({ cert: fs.readFileSync(config.tlsCertFile), key: fs.readFileSync(config.tlsKeyFile) }, app)
  : http.createServer(app);

const wss = new WebSocketServer({ server, ...websocketLimits });

wss.on('connection', (ws, req) => {
  if (wss.clients.size > config.maxWsConnections) {
    const ip = req.socket.remoteAddress ?? 'unknown';
    console.warn(`[ws] 连接数已达上限 ${config.maxWsConnections}，拒绝新连接 ← ${ip}`);
    ws.close(1013, 'server busy');
    return;
  }
  const pathname = req.url?.split('?')[0];
  if (pathname === '/media/ws') mediaRelay.handleConnection(ws);
  else if (pathname === '/ws') handleConnection(ws);
  else ws.close(1008, 'unknown websocket path');
});
// ── 启动 ──────────────────────────────────────────────────

validateConfig();

// 启动报警记录 TTL 清理定时器（每 30 分钟）
setInterval(cleanupExpiredAlerts, 30 * 60 * 1000);
cleanupExpiredAlerts();

// 启动截图 TTL 清理定时器
cleanupScreenshots();
startCleanupTimer();

server.listen(config.port, config.host, () => {
  console.log(`[server] 统一服务 v0.6.3 已启动`);
  console.log(`[server] 隔离通道: ${config.channelId} / 数据目录: ${config.dataDir}`);
  console.log(`[server] HTTP + WS 监听地址: ${config.host}:${config.port}`);
  console.log(`[server] 截图模式: WebSocket 独立推送并备份；HTTP 上传${config.enableHttpScreenshotUpload ? '开启' : '关闭'}`);
  console.log(`[server] 报警记录 TTL: ${config.alertTtlHours} 小时`);
  console.log(`[server] WS 最大连接数: ${config.maxWsConnections}`);
  console.log(`[server] 检测节点幽灵阈值: ${config.deviceOfflineMs / 1000}s / 控制台: ${config.receiverGhostThresholdMs / 1000}s`);
});
