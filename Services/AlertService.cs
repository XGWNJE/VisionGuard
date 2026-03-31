using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Media;
using VisionGuard.Models;

namespace VisionGuard.Services
{
    /// <summary>
    /// 接收检测结果，应用冷却逻辑，触发 AlertTriggered 事件。
    /// 线程安全：Evaluate 可在 ThreadPool 线程调用。
    /// </summary>
    public class AlertService
    {
        public event EventHandler<AlertEvent> AlertTriggered;

        // key = ClassId, value = 上次报警时间
        private readonly Dictionary<int, DateTime> _lastAlertTime = new Dictionary<int, DateTime>();
        private readonly object _lock = new object();

        /// <summary>
        /// 评估本帧检测结果，满足条件时触发 AlertTriggered。
        /// frame 由调用方管理生命周期（此方法内部 Clone）。
        /// </summary>
        public void Evaluate(List<Detection> detections, Bitmap frame, MonitorConfig config)
        {
            if (detections == null || detections.Count == 0) return;

            var triggered = new List<Detection>();
            DateTime now  = DateTime.Now;

            lock (_lock)
            {
                foreach (var det in detections)
                {
                    if (_lastAlertTime.TryGetValue(det.ClassId, out DateTime last))
                    {
                        if ((now - last).TotalSeconds < config.AlertCooldownSeconds)
                            continue;
                    }
                    _lastAlertTime[det.ClassId] = now;
                    triggered.Add(det);
                }
            }

            if (triggered.Count == 0) return;

            Bitmap snapshot;
            try { snapshot = (Bitmap)frame.Clone(); }
            catch { snapshot = null; }

            if (config.PlayAlertSound)
                TryPlaySound();

            if (config.SaveAlertSnapshot && snapshot != null)
                TrySaveSnapshot(snapshot, DateTime.Now);

            AlertTriggered?.Invoke(this, new AlertEvent(triggered.AsReadOnly(), snapshot));
        }

        private static void TryPlaySound()
        {
            try { SystemSounds.Exclamation.Play(); }
            catch { /* 静默失败 */ }
        }

        private static void TrySaveSnapshot(Bitmap bmp, DateTime timestamp)
        {
            try
            {
                string dir = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                    "VisionGuard", "alerts");
                Directory.CreateDirectory(dir);

                string filename = timestamp.ToString("yyyyMMdd_HHmmss_fff") + ".png";
                string path     = Path.Combine(dir, filename);
                bmp.Save(path, System.Drawing.Imaging.ImageFormat.Png);
            }
            catch { /* 存储失败不影响主流程 */ }
        }
    }
}
