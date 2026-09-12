using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using VisionGuard.Utils;

namespace VisionGuard.Services
{
    internal sealed class AlertOutbox
    {
        private const int MaxEntries = 500;
        private readonly object _sync = new object();
        private readonly string _path;
        private List<AlertOutboxEntry> _entries;

        public string RecoveryWarning { get; private set; }

        public AlertOutbox(string path = null)
        {
            _path = path ?? Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "VisionGuard", "winforms-alert-outbox-" + SafeChannelName() + ".json");
            _entries = Load();
        }

        private static string SafeChannelName()
        {
            string channel = Environment.GetEnvironmentVariable("VISIONGUARD_CHANNEL") ?? "vnext";
            return new string(channel.Select(ch => char.IsLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '-' ? ch : '_').ToArray());
        }

        public AlertOutboxEntry[] Snapshot()
        {
            lock (_sync) return _entries.Select(e => new AlertOutboxEntry(e.AlertId, e.PayloadJson, e.CreatedAtUtc)).ToArray();
        }

        public void Enqueue(string alertId, string payloadJson)
        {
            if (string.IsNullOrWhiteSpace(alertId)) throw new ArgumentException("alertId is required", nameof(alertId));
            if (string.IsNullOrWhiteSpace(payloadJson)) throw new ArgumentException("payload is required", nameof(payloadJson));
            lock (_sync)
            {
                _entries.RemoveAll(e => e.AlertId == alertId);
                _entries.Add(new AlertOutboxEntry(alertId, payloadJson, DateTime.UtcNow));
                if (_entries.Count > MaxEntries) _entries = _entries.Skip(_entries.Count - MaxEntries).ToList();
                Save();
            }
        }

        public bool Acknowledge(string alertId)
        {
            lock (_sync)
            {
                bool removed = _entries.RemoveAll(e => e.AlertId == alertId) > 0;
                if (removed) Save();
                return removed;
            }
        }

        private List<AlertOutboxEntry> Load()
        {
            try
            {
                if (!File.Exists(_path)) return new List<AlertOutboxEntry>();
                return SimpleJson.DeserializeStrict<List<AlertOutboxEntry>>(File.ReadAllText(_path))
                    ?? new List<AlertOutboxEntry>();
            }
            catch (Exception ex)
            {
                string corruptPath = _path + ".corrupt-" + DateTime.UtcNow.ToString("yyyyMMddHHmmssfff");
                try
                {
                    File.Move(_path, corruptPath);
                    RecoveryWarning = "报警重试队列损坏，已隔离到 " + corruptPath + "：" + ex.Message;
                }
                catch (Exception moveError)
                {
                    throw new InvalidDataException("报警重试队列损坏且无法隔离：" + _path, moveError);
                }
                return new List<AlertOutboxEntry>();
            }
        }

        private void Save()
        {
            string directory = Path.GetDirectoryName(_path);
            Directory.CreateDirectory(directory);
            string temp = _path + ".tmp";
            File.WriteAllText(temp, SimpleJson.ToJson(_entries));
            if (File.Exists(_path)) File.Replace(temp, _path, null);
            else File.Move(temp, _path);
        }
    }

    internal sealed class AlertOutboxEntry
    {
        public string AlertId { get; set; }
        public string PayloadJson { get; set; }
        public DateTime CreatedAtUtc { get; set; }

        public AlertOutboxEntry() { }

        public AlertOutboxEntry(string alertId, string payloadJson, DateTime createdAtUtc)
        {
            AlertId = alertId;
            PayloadJson = payloadJson;
            CreatedAtUtc = createdAtUtc;
        }
    }
}
