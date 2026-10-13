import assert from 'node:assert/strict';
import test from 'node:test';
import { NODE_DIRECTORY_DEFAULT_WIDTH, NODE_DIRECTORY_MIN_WIDTH, NODE_DIRECTORY_MAX_WIDTH, clampNodeDirectoryWidth, nodeDirectoryKeyStep, nodeDirectoryWidthLimit, readNodeDirectoryWidth, saveNodeDirectoryWidth } from '../src/nodeDirectory.ts';

test('the node directory width stays inside design bounds and keeps room for the detail column', () => {
  assert.equal(clampNodeDirectoryWidth(240), 240);
  assert.equal(clampNodeDirectoryWidth(10), NODE_DIRECTORY_MIN_WIDTH);
  assert.equal(clampNodeDirectoryWidth(9999), NODE_DIRECTORY_MAX_WIDTH);
  assert.equal(clampNodeDirectoryWidth(Number.NaN), NODE_DIRECTORY_DEFAULT_WIDTH);
  // 可用宽度不足时按容器收窄，但仍保留最小宽度。
  assert.equal(clampNodeDirectoryWidth(9999, 900), 480);
  assert.equal(clampNodeDirectoryWidth(9999, 700), 340);
  assert.equal(clampNodeDirectoryWidth(9999, 420), NODE_DIRECTORY_MIN_WIDTH);
  assert.equal(nodeDirectoryWidthLimit(0), NODE_DIRECTORY_MAX_WIDTH);
});

test('a saved width is restored per browser and invalid values fall back to the stylesheet', () => {
  const values = new Map();
  const storage = { getItem: key => values.get(key) ?? null, setItem: (key, value) => { values.set(key, value); } };
  assert.equal(readNodeDirectoryWidth(storage), null);
  saveNodeDirectoryWidth(storage, 352.4);
  assert.equal(readNodeDirectoryWidth(storage), 352);
  values.set('visionguard.node-directory-width', 'not-a-number');
  assert.equal(readNodeDirectoryWidth(storage), null);
  // 存储被禁用（隐私模式）不能让界面报错。
  const blocked = { getItem: () => { throw new Error('blocked'); }, setItem: () => { throw new Error('blocked'); } };
  assert.equal(readNodeDirectoryWidth(blocked), null);
  saveNodeDirectoryWidth(blocked, 300);
});

test('arrow keys step the width and other keys keep their default behaviour', () => {
  assert.equal(nodeDirectoryKeyStep('ArrowRight'), 16);
  assert.equal(nodeDirectoryKeyStep('ArrowLeft'), -16);
  assert.equal(nodeDirectoryKeyStep('ArrowRight', true), 64);
  assert.equal(nodeDirectoryKeyStep('ArrowLeft', true), -64);
  assert.equal(nodeDirectoryKeyStep('Enter'), null);
  assert.equal(nodeDirectoryKeyStep('Home'), null);
});
