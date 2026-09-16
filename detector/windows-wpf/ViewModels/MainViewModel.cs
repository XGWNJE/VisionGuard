using System;
using System.Collections.Generic;
using System.Linq;
using VisionGuard.Models;
using VisionGuard.Services;
using VisionGuard.Utils;
using VisionGuard.Runtime;

namespace VisionGuard.ViewModels
{
    public class MainViewModel : ViewModelBase
    {
        // 子 ViewModel（共享服务）
        public MultiSourceViewModel MultiSourceVm { get; }
        public SettingsViewModel SettingsVm { get; }
        public ServerViewModel ServerVm { get; }

        private readonly ServerPushService _serverPushService;
        private readonly System.Windows.Threading.DispatcherTimer _heartbeatTimer;

        public MainViewModel()
        {
            // 加载持久化设置
            SettingsStore.Load();

            // 创建共享服务
            _serverPushService = new ServerPushService();

            // 子 ViewModel（注入共享服务）
            SettingsVm = new SettingsViewModel();
            SettingsVm.Load();
            MultiSourceVm = new MultiSourceViewModel(_serverPushService, SettingsVm);
            ServerVm = new ServerViewModel(_serverPushService);

            // ── 远控命令路由 ──────────────────────────────────────────
            _serverPushService.CommandReceived += (s, cmd) =>
            {
                System.Windows.Application.Current.Dispatcher.BeginInvoke(() =>
                {
                    if (cmd.Command == "stop-alarm")
                        _serverPushService.SendCommandAck(cmd.Command, false, "当前无报警", cmd.RequestId, cmd.TargetSourceId);
                    else
                        MultiSourceVm.HandleCommand(cmd.TargetSourceId, cmd.Command, cmd.RequestId);
                    RefreshHeartbeat(_serverPushService);
                });
            };

            _serverPushService.SetConfigReceived += (s, kv) =>
            {
                System.Windows.Application.Current.Dispatcher.BeginInvoke(() =>
                {
                    MultiSourceVm.HandleConfig(kv.TargetSourceId, kv.Key, kv.Value, kv.RequestId);
                    RefreshHeartbeat(_serverPushService);
                });
            };

            // 从磁盘恢复设置
            ServerVm.Load();

            // 属性变更自动保存（防抖 500ms，避免 Slider 拖动频繁写盘）
            var saveTimer = new System.Windows.Threading.DispatcherTimer
            {
                Interval = System.TimeSpan.FromMilliseconds(500)
            };
            saveTimer.Tick += (s, e) =>
            {
                saveTimer.Stop();
                SettingsVm.Save();
                MultiSourceVm.Save();
                ServerVm.Save();
            };

            void QueueSave()
            {
                saveTimer.Stop();
                saveTimer.Start();
            }

            SettingsVm.PropertyChanged += (s, e) => QueueSave();
            ServerVm.PropertyChanged += (s, e) => QueueSave();

            // 初始配置服务器连接
            _serverPushService.Configure(
                AppConfig.ServerUrl,
                AppConfig.ApiKey,
                AppConfig.DeviceId,
                ServerVm.DeviceName);

            // 心跳参数定时刷新(3s,与 WinForms 对齐): 确保 isMonitoring/cooldown/confidence/targets 实时同步到接收端
            _heartbeatTimer = new System.Windows.Threading.DispatcherTimer
            {
                Interval = System.TimeSpan.FromSeconds(3)
            };
            _heartbeatTimer.Tick += (s, e) => RefreshHeartbeat(_serverPushService);
            _heartbeatTimer.Start();
        }

        /// <summary>程序退出前清理资源。</summary>
        public void Shutdown()
        {
            // 停止心跳定时器
            _heartbeatTimer?.Stop();

            // 强制保存一次当前设置
            SettingsVm.Save();
            MultiSourceVm.Save();
            ServerVm.Save();

            // 停止监控（会释放 ONNX 引擎）
            MultiSourceVm.Dispose();
            // 释放 WebSocket 连接与事件循环线程
            _serverPushService.Dispose();
        }

        /// <summary>远控命令/配置变更后刷新心跳参数并立即推送。</summary>
        private void RefreshHeartbeat(ServerPushService sps)
        {
            var sourceStatuses = MultiSourceVm.Statuses;
            object[] heartbeatSources = sourceStatuses.Select(x =>
                {
                    var slot = MultiSourceVm.Sources.First(s => s.SourceId == x.SourceId);
                    return (object)new Dictionary<string, object>
                    {
                        ["sourceId"] = x.SourceId, ["sourceName"] = x.SourceName,
                        ["isMonitoring"] = x.IsMonitoring, ["isReady"] = x.IsReady,
                        ["modelKey"] = x.ModelKey, ["actualFps"] = x.ActualFps,
                        ["error"] = x.Error, ["cooldown"] = slot.Cooldown,
                        ["confidence"] = slot.ThresholdPercent / 100d, ["targets"] = slot.Targets,
                        ["targetSamplingRate"] = Net472Compat.Clamp(slot.TargetFps, 1, 5),
                    };
                }).ToArray();
            sps.UpdateHeartbeatParams(
                isMonitoring: sourceStatuses.Any(x => x.IsMonitoring),
                isReady: sourceStatuses.Any(x => x.IsReady),
                cooldown: MultiSourceVm.Sources[0].Cooldown,
                confidence: MultiSourceVm.Sources[0].ThresholdPercent / 100f,
                targets: MultiSourceVm.Sources[0].Targets,
                targetSamplingRate: MultiSourceVm.Sources[0].TargetFps,
                modelKey: MultiSourceVm.Sources[0].ModelKey,
                modelOptions: Utils.ModelManager.ModelKeys,
                canSwitchModelWhileMonitoring: false,
                sources: heartbeatSources);
            sps.SendHeartbeatNow();
            MultiSourceVm.RefreshSummary();
        }
    }
}
