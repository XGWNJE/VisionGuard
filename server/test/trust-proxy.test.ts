import assert from 'node:assert/strict';
import http from 'node:http';
import express from 'express';
import test from 'node:test';

// proxy-addr 2.0.8 修的漏洞只在「信任代理写成 IPv6 子网」时成立，
// 例如信任 `::ffff:0:0/96`：攻击者只要发一条 `X-Forwarded-For: ::ffff:1.2.3.4`，
// 伪造地址就会被当成可信跳、并成为 req.ip 与限流键。
// 本项目实际用的是跳数写法 `trust proxy = 1`（src/index.ts），只能信任紧邻的一跳，
// 下面的用例把这份口径钉住：命中公告的写法让 XFF 顶掉 socket 地址，我们的写法不会。

const forwarded = '203.0.113.7';
const mapped = `::ffff:${forwarded}`;

async function ipSeenBy(trustProxy: string | number, xForwardedFor?: string) {
  const app = express();
  app.set('trust proxy', trustProxy);
  app.get('/api/session', (req, res) => { res.json({ ip: req.ip }); });
  const server = http.createServer(app);
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const url = `http://127.0.0.1:${(server.address() as any).port}/api/session`;
    const response = await fetch(url, { headers: xForwardedFor === undefined ? {} : { 'X-Forwarded-For': xForwardedFor } });
    return ((await response.json()) as { ip?: string }).ip;
  } finally {
    await new Promise<void>(resolve => server.close(() => resolve()));
  }
}

test('hop-count trust proxy only accepts the immediate hop and never the client-supplied mapped address', async () => {
  assert.equal(await ipSeenBy(1), '127.0.0.1');
  assert.equal(await ipSeenBy(1, forwarded), forwarded);
  // 真实链路是「客户端 → nginx」：nginx 追加的跳是我们唯一信任的那一跳。
  assert.equal(await ipSeenBy(1, `${forwarded}, 10.0.0.1`), '10.0.0.1');
});

test('the IPv6 subnet configuration named by the advisory is the one that lets a forged hop through', async () => {
  assert.equal(await ipSeenBy('::ffff:0:0/96', mapped), mapped);
  assert.equal(await ipSeenBy('::ffff:0:0/96', forwarded), forwarded);
});

test('an IPv4-mapped client address stays outside the trusted hop under our configuration', async () => {
  assert.equal(await ipSeenBy(1, mapped), mapped, '跳数信任下不会把客户端自己写的映射地址当成代理跳');
});
