import assert from 'node:assert/strict';
import test from 'node:test';

// 协议版本闸门只服务上线部署实例；本地/测试实例放行所有版本，
// 因此这里直接按两种实例形态构造闸门，不依赖进程环境变量。
const { PROTOCOL_VERSION, createHttpVersionGate, isCurrentVersion, wsVersionGate, protocolNoiseStats, resetProtocolNoise, flushProtocolNoise } =
  require('../src/services/protocol') as typeof import('../src/services/protocol');

test('a deployed instance serves only its own version and drops everything else', () => {
  assert.equal(isCurrentVersion(PROTOCOL_VERSION), true);
  assert.equal(isCurrentVersion('0.0.1'), false);
  assert.equal(isCurrentVersion(undefined), false);

  // 旧协议（API Key 时代没有版本字段）与旧版本都出局，且不回任何东西。
  resetProtocolNoise();
  assert.equal(wsVersionGate({ type: 'auth', apiKey: 'legacy' }, true), false);
  assert.equal(wsVersionGate({ type: 'auth', version: '0.0.1' }, true), false);
  assert.equal(wsVersionGate({ type: 'auth', version: PROTOCOL_VERSION }, true), true);

  const calls: string[] = [];
  const res = () => ({
    status(code: number) { calls.push(`status:${code}`); return this; },
    end() { calls.push('end'); return this; }
  });
  const gate = createHttpVersionGate(true);
  gate({ headers: {} }, res(), () => calls.push('next'));
  gate({ headers: { 'x-visionguard-version': '0.0.1' } }, res(), () => calls.push('next'));
  assert.deepEqual(calls, ['status:426', 'end', 'status:426', 'end']);
  gate({ headers: { 'x-visionguard-version': PROTOCOL_VERSION } }, res(), () => calls.push('next'));
  assert.deepEqual(calls.slice(4), ['next']);
  resetProtocolNoise();
});

test('a local or test instance serves every version so link and UI checks need no matching build', () => {
  resetProtocolNoise();
  assert.equal(wsVersionGate({ type: 'auth', apiKey: 'legacy' }, false), true);
  assert.equal(wsVersionGate({ type: 'auth', version: '0.0.1' }, false), true);
  let next = 0;
  createHttpVersionGate(false)({ headers: {} }, { status: () => ({ end: () => {} }) }, () => { next += 1; });
  assert.equal(next, 1);
  assert.equal(protocolNoiseStats(), null);
});

test('legacy traffic is aggregated by version instead of logged per attempt', () => {
  resetProtocolNoise();
  for (let i = 0; i < 3; i += 1) wsVersionGate({ type: 'auth', version: '0.0.1' }, true);
  wsVersionGate({ type: 'auth' }, true);
  const stats = protocolNoiseStats();
  assert.equal(stats?.connections, 4);
  assert.equal(stats?.httpRequests, 0);
  assert.deepEqual(stats?.byVersion, { '0.0.1': 3, '(未声明)': 1 });

  const lines: string[] = [];
  const original = console.info;
  console.info = (...args: unknown[]) => { lines.push(args[1] as string); };
  try { flushProtocolNoise(); } finally { console.info = original; }
  assert.equal(lines.length, 1);
  const summary = JSON.parse(lines[0]);
  assert.equal(summary.event, 'legacy-protocol-dropped');
  assert.equal(summary.servedVersion, PROTOCOL_VERSION);
  assert.equal(summary.connections, 4);
  assert.equal(protocolNoiseStats(), null);
});
