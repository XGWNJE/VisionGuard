import assert from 'node:assert/strict';
import dgram from 'node:dgram';
import test from 'node:test';
import type { AddressInfo } from 'node:net';
import { beaconDestinations, startLanBeacon } from '../src/services/LanBeacon';

/** 起一个本地 UDP 接收器（端口由系统分配），返回端口、收到的载荷与关闭函数。 */
async function receive(): Promise<{ port: number; packets: string[]; close: () => void }> {
  const socket = dgram.createSocket('udp4');
  const packets: string[] = [];
  socket.on('message', message => packets.push(message.toString('utf8')));
  await new Promise<void>((resolve, reject) => {
    socket.once('error', reject);
    socket.bind(0, '127.0.0.1', () => resolve());
  });
  return { port: (socket.address() as AddressInfo).port, packets, close: () => socket.close() };
}

test('局域网信标按约定载荷广播，停止后不再发送', async () => {
  const receiver = await receive();
  const stop = startLanBeacon({
    port: receiver.port,
    channel: 'local-service',
    servicePort: 4321,
    version: '0.0.0-test',
    intervalMs: 120,
    destinations: ['127.0.0.1'],
  });
  try {
    await new Promise(resolve => setTimeout(resolve, 400));
    assert.ok(receiver.packets.length >= 1, '没有收到信标广播');
    const payload = JSON.parse(receiver.packets[0]) as Record<string, unknown>;
    assert.equal(payload.service, 'visionguard-local');
    assert.equal(payload.channel, 'local-service');
    assert.equal(payload.port, 4321);
    assert.equal(payload.version, '0.0.0-test');
  } finally {
    stop();
  }
  const seen = receiver.packets.length;
  await new Promise(resolve => setTimeout(resolve, 400));
  assert.equal(receiver.packets.length, seen, '停止后仍在广播');
  receiver.close();
});

test('信标目标包含全局广播，且只取非内部 IPv4 网卡的定向广播', () => {
  const targets = beaconDestinations();
  assert.ok(targets.includes('255.255.255.255'));
  for (const target of targets) {
    assert.match(target, /^\d{1,3}(\.\d{1,3}){3}$/);
    assert.notEqual(target, '127.0.0.1');
  }
});
