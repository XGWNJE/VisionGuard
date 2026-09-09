using System;
using System.Threading;

namespace VisionGuard.Services
{
    public sealed class ResidentBridge : IDisposable
    {
        private readonly EventWaitHandle _running;
        private readonly EventWaitHandle _shutdown;
        private readonly RegisteredWaitHandle _shutdownWait;

        public ResidentBridge(string applicationId, Action shutdownAction)
        {
            if (string.IsNullOrWhiteSpace(applicationId)) throw new ArgumentException("Application ID is required.", nameof(applicationId));
            if (shutdownAction == null) throw new ArgumentNullException(nameof(shutdownAction));

            _running = new EventWaitHandle(false, EventResetMode.ManualReset, RunningEventName(applicationId));
            _shutdown = new EventWaitHandle(false, EventResetMode.AutoReset, ShutdownEventName(applicationId));
            _running.Set();
            _shutdownWait = ThreadPool.RegisterWaitForSingleObject(_shutdown, (_, timedOut) =>
            {
                if (!timedOut) shutdownAction();
            }, null, Timeout.Infinite, true);
        }

        public static string RunningEventName(string applicationId) => $"Local\\VisionGuard.{applicationId}.Running";
        public static string ShutdownEventName(string applicationId) => $"Local\\VisionGuard.{applicationId}.Shutdown";

        public void Dispose()
        {
            _shutdownWait.Unregister(null);
            _running.Reset();
            _shutdown.Dispose();
            _running.Dispose();
        }
    }
}
