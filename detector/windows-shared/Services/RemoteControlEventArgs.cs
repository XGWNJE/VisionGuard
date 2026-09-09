using System;

namespace VisionGuard.Services
{
    public sealed class RemoteCommandEventArgs : EventArgs
    {
        public string Command { get; }
        public string RequestId { get; }
        public string TargetSourceId { get; }

        public RemoteCommandEventArgs(string command, string requestId, string targetSourceId = "")
        {
            Command = command ?? string.Empty;
            RequestId = requestId ?? string.Empty;
            TargetSourceId = targetSourceId ?? string.Empty;
        }
    }

    public sealed class RemoteSetConfigEventArgs : EventArgs
    {
        public string Key { get; }
        public string Value { get; }
        public string RequestId { get; }
        public string TargetSourceId { get; }

        public RemoteSetConfigEventArgs(string key, string value, string requestId, string targetSourceId = "")
        {
            Key = key ?? string.Empty;
            Value = value ?? string.Empty;
            RequestId = requestId ?? string.Empty;
            TargetSourceId = targetSourceId ?? string.Empty;
        }
    }
}
