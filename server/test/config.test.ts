import assert from 'node:assert/strict';
import test from 'node:test';
import { parseBindHost } from '../src/config';

test('parseBindHost defaults to loopback', () => {
  assert.equal(parseBindHost(undefined), '127.0.0.1');
  assert.equal(parseBindHost('   '), '127.0.0.1');
});

test('parseBindHost accepts an explicit override', () => {
  assert.equal(parseBindHost(' 0.0.0.0 '), '0.0.0.0');
});
