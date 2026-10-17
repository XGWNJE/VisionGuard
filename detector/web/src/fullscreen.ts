/** Browsers require a user gesture. Try once; Escape must not trigger re-entry. */
export function installDefaultFullscreen(doc: Document, changed: () => void): () => void {
  const removeGestures = () => {
    doc.removeEventListener('pointerup', enter, { capture: true });
    doc.removeEventListener('keydown', enter, { capture: true });
  };
  const enter = (event: Event) => {
    if (event.type === 'keydown' && !['Enter', ' '].includes((event as KeyboardEvent).key)) return;
    removeGestures();
    // The explicit toggle handles its own gesture, avoiding a second request.
    if (event.composedPath().some(target => target instanceof Element && target.hasAttribute('data-camera-fullscreen'))) return;
    if (!doc.fullscreenElement && doc.fullscreenEnabled) void doc.documentElement.requestFullscreen({ navigationUI: 'hide' }).catch(() => {});
  };
  if (!doc.fullscreenElement && doc.fullscreenEnabled) {
    doc.addEventListener('pointerup', enter, { capture: true });
    doc.addEventListener('keydown', enter, { capture: true });
  }
  doc.addEventListener('fullscreenchange', changed);
  return () => { removeGestures(); doc.removeEventListener('fullscreenchange', changed); };
}

export async function toggleFullscreen(doc: Document): Promise<void> {
  if (doc.fullscreenElement) await doc.exitFullscreen();
  else if (doc.fullscreenEnabled) await doc.documentElement.requestFullscreen({ navigationUI: 'hide' });
  else throw new Error('当前浏览器不支持页面全屏');
}
