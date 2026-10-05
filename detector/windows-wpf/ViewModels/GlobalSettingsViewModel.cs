namespace VisionGuard.Detector.Windows.ViewModels
{
    /// <summary>
    /// 「全局设定」页的容器 ViewModel。
    ///
    /// 两个子 ViewModel 仍是各自领域的唯一实现，这里只做页面级组合：
    /// 环境设置（推理设备、模型资源）保持在 <see cref="Environment"/>，
    /// 连接与设备身份保持在 <see cref="Connection"/>，避免把两套状态揉成一个巨型类。
    /// </summary>
    public sealed class GlobalSettingsViewModel : ViewModelBase
    {
        public string[] AppearanceOptions { get; } = new[] { "跟随系统", "浅色", "深色" };
        public int AppearanceIndex {
            get => Themes.ThemeManager.Mode == "dark" ? 2 : Themes.ThemeManager.Mode == "light" ? 1 : 0;
            set { if (value < 0 || value > 2 || value == AppearanceIndex) return; AppearanceStatus = Themes.ThemeManager.SetMode(new[] { "system", "light", "dark" }[value]) ? "外观已保存" : "外观保存失败，请重试"; OnPropertyChanged(); OnPropertyChanged(nameof(AppearanceStatus)); }
        }
        public string AppearanceStatus { get; private set; } = "系统外观不可用时使用浅色；高对比度优先使用系统颜色。";
        /// <summary>推理设备与模型资源。</summary>
        public SettingsViewModel Environment { get; }

        /// <summary>服务器、设备身份、驻留与客户端更新。</summary>
        public ServerViewModel Connection { get; }

        public GlobalSettingsViewModel(SettingsViewModel environment, ServerViewModel connection)
        {
            Environment = environment;
            Connection = connection;
        }
    }
}
