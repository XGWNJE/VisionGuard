namespace VisionGuard.Services
{
    public enum MonitorFailureKind
    {
        None,
        Capture,
        BlackFrame,
        Inference,
        Processing,
    }

    public static class MonitorFailurePolicy
    {
        public static bool RequiresGlobalStop(MonitorFailureKind kind, string activeBackend)
            => kind == MonitorFailureKind.Inference
                && string.Equals(activeBackend, "DirectML", System.StringComparison.OrdinalIgnoreCase);
    }
}
