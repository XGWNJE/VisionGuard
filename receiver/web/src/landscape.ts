/** A browser may require fullscreen or reject orientation locking. */
export function requestLandscape() {
  const orientation = screen.orientation as ScreenOrientation & { lock?: (value: string) => Promise<void> };
  void orientation?.lock?.('landscape').catch(() => {});
}
