#nullable enable
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;

namespace VisionGuard.Services
{
    public sealed class AlertOutbox
    {
        private const int MaxEntries = 500;
        private readonly object _sync = new();
        private readonly string _path;
        private List<AlertOutboxEntry> _entries;

        public string? RecoveryWarning { get; private set; }

        public AlertOutbox(string? path = null)
        {
            _path = path ?? Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "VisionGuard", $"alert-outbox-{SafeChannelName()}.json");
            _entries = Load();
        }

        private static string SafeChannelName()
        {
            var channel = Environment.GetEnvironmentVariable("VISIONGUARD_CHANNEL") ?? "vnext";
            return new string(channel.Select(ch => char.IsLetterOrDigit(ch) || ch is '.' or '_' or '-' ? ch : '_').ToArray());
        }

        public IReadOnlyList<AlertOutboxEntry> Snapshot()
        {
            lock (_sync) return _entries.Select(entry => entry with { }).ToArray();
        }

        public void Enqueue(string alertId, string payloadJson)
        {
            if (string.IsNullOrWhiteSpace(alertId)) throw new ArgumentException("alertId is required", nameof(alertId));
            if (string.IsNullOrWhiteSpace(payloadJson)) throw new ArgumentException("payload is required", nameof(payloadJson));
            lock (_sync)
            {
                var existing = _entries.FindIndex(entry => entry.AlertId == alertId);
                var entry = new AlertOutboxEntry(alertId, payloadJson, DateTime.UtcNow);
                if (existing >= 0) _entries[existing] = entry;
                else _entries.Add(entry);
                if (_entries.Count > MaxEntries) _entries = _entries.Skip(_entries.Count - MaxEntries).ToList();
                Save();
            }
        }

        public bool Acknowledge(string alertId)
        {
            lock (_sync)
            {
                var removed = _entries.RemoveAll(entry => entry.AlertId == alertId) > 0;
                if (removed) Save();
                return removed;
            }
        }

        private List<AlertOutboxEntry> Load()
        {
            try
            {
                if (!File.Exists(_path)) return new();
                return JsonSerializer.Deserialize<List<AlertOutboxEntry>>(File.ReadAllText(_path)) ?? new();
            }
            catch (Exception ex)
            {
                var corruptPath = $"{_path}.corrupt-{DateTime.UtcNow:yyyyMMddHHmmssfff}";
                try
                {
                    File.Move(_path, corruptPath, false);
                    RecoveryWarning = $"报警重试队列损坏，已隔离到 {corruptPath}：{ex.Message}";
                }
                catch (Exception moveError)
                {
                    throw new InvalidDataException($"报警重试队列损坏且无法隔离：{_path}", moveError);
                }
                return new();
            }
        }

        private void Save()
        {
            var directory = Path.GetDirectoryName(_path)!;
            Directory.CreateDirectory(directory);
            var temp = _path + ".tmp";
            File.WriteAllText(temp, JsonSerializer.Serialize(_entries));
            File.Move(temp, _path, true);
        }
    }

    public sealed record AlertOutboxEntry(string AlertId, string PayloadJson, DateTime CreatedAtUtc);
}
