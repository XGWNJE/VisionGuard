namespace VisionGuard.ViewModels
{
    /// <summary>
    /// 「全局设定」页的容器 ViewModel（原「运行环境」与「连接」两页合一）。
    ///
    /// 两个子 ViewModel 仍是各自领域的唯一实现，这里只做页面级组合：
    /// 环境设置（推理设备、容量、模型资源）保持在 <see cref="Environment"/>，
    /// 连接与设备身份保持在 <see cref="Connection"/>，避免把两套状态揉成一个巨型类。
    /// </summary>
    public sealed class GlobalSettingsViewModel : ViewModelBase
    {
        /// <summary>推理设备与模型资源（原 SettingsViewModel）。</summary>
        public SettingsViewModel Environment { get; }

        /// <summary>服务器、设备身份、驻留与客户端更新（原 ServerViewModel）。</summary>
        public ServerViewModel Connection { get; }

        public GlobalSettingsViewModel(SettingsViewModel environment, ServerViewModel connection)
        {
            Environment = environment;
            Connection = connection;
        }
    }
}
