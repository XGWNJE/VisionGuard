// ┌─────────────────────────────────────────────────────────┐
// │ AlertService.cs                                         │
// │ 角色：报警判定（冷却逻辑）+ 截图本地缓存管理              │
// │ 依赖：无（纯逻辑层）                                    │
// │ 对外 API：Evaluate(), GetSnapshotPath()      │
// │ 缓存策略：1GB / 7天 / 5000张上限，LRU 清理               │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using VisionGuard.Detector.Windows.Models;
using VisionGuard.Detector.Windows.Utils;

namespace VisionGuard.Detector.Windows.Services
{
    /// <summary>
    /// 接收检测结果，应用冷却逻辑，触发 AlertTriggered 事件。
    /// 所有通知逻辑由 Android 端处理。
    /// 线程安全：Evaluate 可在任意线程调用。
    /// </summary>
    public class AlertService : IDisposable
    {
        private readonly string _sourceId;
        private readonly string _accountScope = AccountSession.ScopeKey;
        private string _sourceName;

        public AlertService(string sourceId = "default", string sourceName = "默认来源")
        {
            _sourceId = string.IsNullOrWhiteSpace(sourceId) ? "default" : sourceId;
            _sourceName = string.IsNullOrWhiteSpace(sourceName) ? "默认来源" : sourceName;
        }

        public void UpdateSourceName(string sourceName)
            => _sourceName = string.IsNullOrWhiteSpace(sourceName) ? "默认来源" : sourceName;
        // ── 对外事件 ─────────────────────────────────────────────────
        public event EventHandler<AlertEvent> AlertTriggered;

        // ── 冷却（全局，以时间为基准）────────────────────────────────
        private DateTime _lastAlertTime = DateTime.MinValue;
        private readonly object _cooldownLock = new object();

        // ── 缓存约束 ─────────────────────────────────────────────────
        private const long MAX_CACHE_SIZE_BYTES = 1024L * 1024 * 1024; // 1 GB
        private const int MAX_CACHE_COUNT = 5000;
        private const long MAX_CACHE_AGE_MS = 7L * 24 * 60 * 60 * 1000; // 7 天

        private static readonly object CacheLock = new object();
        private bool _disposed;

        // ── 评估入口 ─────────────────────────────────────────────────

        /// <summary>
        /// 评估本帧检测结果，满足冷却条件时触发报警。
        /// 使用推理帧副本绘制检测框后保存，确保坐标完全匹配。
        /// </summary>
        public void Evaluate(List<Detection> detections, MonitorConfig config,
                             Dictionary<string, long> timings, Bitmap inferenceFrame)
        {
            if (detections == null || detections.Count == 0 || _accountScope != AccountSession.ScopeKey) return;

            DateTime now = NtpSync.UtcNow;

            lock (_cooldownLock)
            {
                // 全局冷却：触发报警后，冷却时间内不重复触发
                if ((now - _lastAlertTime).TotalSeconds < config.AlertCooldownSeconds)
                    return;

                _lastAlertTime = now;
            }

            var sw = Stopwatch.StartNew();

            // 使用推理帧的副本绘制检测框（确保坐标完全匹配）
            Bitmap snapshot = null;
            try
            {
                snapshot = (Bitmap)inferenceFrame.Clone();

                // 在截图上绘制检测框
                SnapshotRenderer.DrawDetections(snapshot, detections);
            }
            catch
            {
                snapshot?.Dispose();
                snapshot = null;
            }

            long renderedMs = sw.ElapsedMilliseconds;
            // 生成 alertId，用于本地截图文件名和服务端追踪
            string alertId = Guid.NewGuid().ToString();

            if (config.SaveAlertSnapshot && snapshot != null)
                TrySaveSnapshot(snapshot, alertId);

            long alertMs = sw.ElapsedMilliseconds;
            long processMs = timings["captureMs"] + timings["preprocessMs"]
                           + timings["inferMs"] + timings["parseMs"] + alertMs;
            // 构建新的 timings 字典，不修改调用方传入的字典
            var finalTimings = new Dictionary<string, long>(timings)
            {
                ["processMs"] = processMs,
            };

            // 触发事件（传递本帧所有检测结果）
            AlertTriggered?.Invoke(this, new AlertEvent(alertId, detections.AsReadOnly(), snapshot, finalTimings, _sourceId, _sourceName));
            if (MediaDiagnostics.Enabled)
                MediaDiagnostics.Write($"[MediaPerf] event=alert renderMs={renderedMs} saveMs={alertMs-renderedMs} dispatchMs={sw.ElapsedMilliseconds-alertMs} totalMs={sw.ElapsedMilliseconds}");
        }

        // ── 截图缓存管理 ─────────────────────────────────────────────

        private void TrySaveSnapshot(Bitmap bmp, string alertId)
        {
            try
            {
                string dir = DirectoryForScope(_accountScope);
                Directory.CreateDirectory(dir);

                string filename = alertId + ".png";
                string path     = Path.Combine(dir, filename);
                lock (CacheLock)
                {
                    bmp.Save(path, System.Drawing.Imaging.ImageFormat.Png);
                    CleanupCache(dir, true);
                }
            }
            catch { }
        }

        /// <summary>
        /// 根据 alertId 获取本地截图文件路径。
        /// </summary>
        public static string GetSnapshotPath(string alertId)
        {
            return Path.Combine(AlertDirectory, alertId + ".png");
        }

        private static string AlertDirectory => DirectoryForScope(AccountSession.ScopeKey);

        private static string DirectoryForScope(string scope) => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "VisionGuard", "accounts", scope, "alerts");

        /// <summary>
        /// 清理截图缓存：满足 1GB / 7天 / 5000张 约束（LRU）。
        /// </summary>
        public static object MaintainCache(bool clean)
        {
            lock (CacheLock) return CleanupCache(AlertDirectory, clean);
        }

        private static bool SafeDirectory(string directory)
        {
            for (var current = new DirectoryInfo(directory); current != null; current = current.Parent)
                if (!current.Exists || (current.Attributes & FileAttributes.ReparsePoint) != 0) return false;
            return true;
        }

        private static object CleanupCache(string dir, bool clean)
        {
            long count = 0, bytes = 0, candidates = 0, candidateBytes = 0, removed = 0, removedBytes = 0, failed = 0;
            if (Directory.Exists(dir) && SafeDirectory(dir))
            {
                var files = new DirectoryInfo(dir).GetFiles("*.png")
                    .Where(f => (f.Attributes & FileAttributes.ReparsePoint) == 0 && System.Text.RegularExpressions.Regex.IsMatch(f.Name, @"^[A-Za-z0-9_-]{8,128}\.png$"))
                    .OrderBy(f => f.LastWriteTimeUtc).ToList();
                count = files.Count; bytes = files.Sum(f => f.Length);
                long retainedBytes = bytes, retainedCount = count;
                foreach (var file in files)
                {
                    var length = file.Length;
                    if ((DateTime.UtcNow - file.LastWriteTimeUtc).TotalMilliseconds <= MAX_CACHE_AGE_MS && retainedCount <= MAX_CACHE_COUNT && retainedBytes <= MAX_CACHE_SIZE_BYTES) continue;
                    candidates++; candidateBytes += length;
                    if (!clean) { retainedBytes -= length; retainedCount--; continue; }
                    try { file.Delete(); removed++; removedBytes += length; retainedBytes -= length; retainedCount--; }
                    catch { failed++; }
                }
            }
            if (Directory.Exists(dir) && !SafeDirectory(dir)) failed++;
            return new Dictionary<string, object>
            {
                ["categories"] = new object[] { new Dictionary<string, object> {
                    ["id"] = "screenshots", ["label"] = "截图缓存（7 天 / 1 GB / 5000 张）",
                    ["files"] = count, ["bytes"] = bytes, ["cleanableFiles"] = candidates, ["cleanableBytes"] = candidateBytes } },
                ["removedFiles"] = removed, ["removedBytes"] = removedBytes,
                ["releasedBytes"] = null, ["failedFiles"] = failed // File bytes are known; reclaimed filesystem blocks are not.
            };
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
        }
    }
}
