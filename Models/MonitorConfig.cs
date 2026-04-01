using System.Collections.Generic;
using System.Drawing;

namespace VisionGuard.Models
{
    public class MonitorConfig
    {
        public Rectangle CaptureRegion { get; set; } = new Rectangle(0, 0, 640, 480);
        public float ConfidenceThreshold { get; set; } = 0.45f;
        public float IouThreshold { get; set; } = 0.45f;
        public int TargetFps { get; set; } = 2;
        public int IntraOpNumThreads { get; set; } = 2;
        // 要监控的类名集合，空集合 = 检测全部（空白配置也视为全部）
        public HashSet<string> WatchedClasses { get; set; } = new HashSet<string>();
        public int AlertCooldownSeconds { get; set; } = 5;
        public bool SaveAlertSnapshot { get; set; } = true;
        public bool   PlayAlertSound   { get; set; } = true;
        public string AlertSoundPath   { get; set; } = string.Empty;
    }
}
