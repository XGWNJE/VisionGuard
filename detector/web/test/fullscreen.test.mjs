import assert from 'node:assert/strict';
import test, { after } from 'node:test';
import { loadTypescriptModule } from './helpers.mjs';

const { installDefaultFullscreen, toggleFullscreen } = loadTypescriptModule('..', 'src/fullscreen.ts');
const originalElement = globalThis.Element;
globalThis.Element = class { hasAttribute() { return false; } };
after(() => { if (originalElement === undefined) delete globalThis.Element; else globalThis.Element = originalElement; });

function browser() {
  const doc = new EventTarget();
  const requests = [];
  doc.fullscreenEnabled = true;
  doc.fullscreenElement = null;
  doc.documentElement = { requestFullscreen: async options => {
    requests.push(options); doc.fullscreenElement = doc.documentElement; doc.dispatchEvent(new Event('fullscreenchange'));
  } };
  doc.exitFullscreen = async () => { doc.fullscreenElement = null; doc.dispatchEvent(new Event('fullscreenchange')); };
  return { doc, requests };
}

test('default fullscreen waits for a gesture and respects an explicit exit', async () => {
  const { doc, requests } = browser();
  const changes = [];
  const dispose = installDefaultFullscreen(doc, () => changes.push(Boolean(doc.fullscreenElement)));
  assert.equal(requests.length, 0);
  doc.dispatchEvent(new Event('pointerup'));
  assert.deepEqual(requests, [{ navigationUI: 'hide' }]);
  await doc.exitFullscreen();
  doc.dispatchEvent(new Event('pointerup'));
  assert.equal(requests.length, 1);
  assert.deepEqual(changes, [true, false]);
  dispose();
  doc.dispatchEvent(new Event('fullscreenchange'));
  assert.equal(changes.length, 2);
});

test('typing does not enter fullscreen; the first Enter or Space can', () => {
  for (const key of ['Enter', ' ']) {
    const { doc, requests } = browser();
    const dispose = installDefaultFullscreen(doc, () => {});
    const typed = new Event('keydown'); typed.key = 'a'; doc.dispatchEvent(typed);
    assert.equal(requests.length, 0);
    const accepted = new Event('keydown'); accepted.key = key; doc.dispatchEvent(accepted);
    assert.equal(requests.length, 1);
    dispose();
  }
});

test('the fullscreen button handles its gesture once and can exit', async () => {
  const original = globalThis.Element;
  globalThis.Element = class { hasAttribute(name) { return name === 'data-camera-fullscreen'; } };
  try {
    const { doc, requests } = browser();
    const dispose = installDefaultFullscreen(doc, () => {});
    const event = new Event('pointerup'); event.composedPath = () => [new globalThis.Element()];
    doc.dispatchEvent(event);
    assert.equal(requests.length, 0);
    await toggleFullscreen(doc);
    assert.equal(requests.length, 1);
    await toggleFullscreen(doc);
    assert.equal(doc.fullscreenElement, null);
    doc.dispatchEvent(new Event('pointerup'));
    assert.equal(requests.length, 1);
    dispose();
  } finally { if (original === undefined) delete globalThis.Element; else globalThis.Element = original; }
});

test('unsupported or refused fullscreen keeps normal browsing and permits a manual retry', async () => {
  const { doc, requests } = browser();
  doc.fullscreenEnabled = false;
  const dispose = installDefaultFullscreen(doc, () => {});
  doc.dispatchEvent(new Event('pointerup'));
  assert.equal(requests.length, 0);
  await assert.rejects(toggleFullscreen(doc), /不支持页面全屏/);
  dispose();
  doc.fullscreenEnabled = true;
  doc.documentElement.requestFullscreen = async () => { throw new Error('Fullscreen denied'); };
  const stop = installDefaultFullscreen(doc, () => {});
  doc.dispatchEvent(new Event('pointerup'));
  await assert.rejects(toggleFullscreen(doc), /Fullscreen denied/);
  stop();
});
