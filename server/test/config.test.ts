import assert from 'node:assert/strict';
import test from 'node:test';
import { config, parseBindHost } from '../src/config';

test('maxSourcesPerDetector defaults to the detector ceiling', () => {
  // 默认值必须与检测端 MultiSourceMonitorCoordinator.MaximumSourceLimit（16）对齐。
  // 曾经默认 4：检测端收到 auth-result/heartbeat-ack 的 maxSources=4 后新增来源按钮在
  // 4 路即变灰，且 4 路正好一页装下，分页页脚从不出现——被误判成布局崩了。
  if (process.env.MAX_SOURCES_PER_DETECTOR !== undefined) return;
  assert.equal(config.maxSourcesPerDetector, 16);
});

test('parseBindHost defaults to loopback', () => {
  assert.equal(parseBindHost(undefined), '127.0.0.1');
  assert.equal(parseBindHost('   '), '127.0.0.1');
});

test('parseBindHost accepts an explicit override', () => {
  assert.equal(parseBindHost(' 0.0.0.0 '), '0.0.0.0');
});
