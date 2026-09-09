using System;
using System.Threading;

namespace VisionGuard.Utils
{
    public sealed class SingleInstanceGuard : IDisposable
    {
        private readonly Mutex _mutex;
        private readonly bool _ownsMutex;

        public bool IsPrimaryInstance => _ownsMutex;

        public SingleInstanceGuard(string applicationId)
        {
            if (string.IsNullOrWhiteSpace(applicationId))
                throw new ArgumentException("Application ID is required.", nameof(applicationId));

            _mutex = new Mutex(initiallyOwned: true, name: $"Local\\VisionGuard.{applicationId}", createdNew: out _ownsMutex);
        }

        public void Dispose()
        {
            if (_ownsMutex)
            {
                try { _mutex.ReleaseMutex(); }
                catch (ApplicationException) { }
            }
            _mutex.Dispose();
        }
    }
}
