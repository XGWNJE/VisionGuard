using System;
using System.Collections.Generic;
using VisionGuard.Utils;

namespace VisionGuard.ViewModels
{
    public class SettingsViewModel : ViewModelBase
    {
        private int _threshold = 45;
        public int Threshold
        {
            get => _threshold;
            set => SetProperty(ref _threshold, value);
        }

        private int _samplingRate = 3;
        public int SamplingRate
        {
            get => _samplingRate;
            set => SetProperty(ref _samplingRate, value);
        }

        private int _cooldown = 5;
        public int Cooldown
        {
            get => _cooldown;
            set => SetProperty(ref _cooldown, value);
        }

        private int _selectedModelIndex;
        public int SelectedModelIndex
        {
            get => _selectedModelIndex;
            set => SetProperty(ref _selectedModelIndex, value);
        }

        // ── 监控目标（6 类，与旧代码行为对齐）────────────────────────
        private bool _watchPerson = true;
        public bool WatchPerson
        {
            get => _watchPerson;
            set => SetProperty(ref _watchPerson, value);
        }

        private bool _watchBicycle;
        public bool WatchBicycle
        {
            get => _watchBicycle;
            set => SetProperty(ref _watchBicycle, value);
        }

        private bool _watchCar;
        public bool WatchCar
        {
            get => _watchCar;
            set => SetProperty(ref _watchCar, value);
        }

        private bool _watchMotorcycle;
        public bool WatchMotorcycle
        {
            get => _watchMotorcycle;
            set => SetProperty(ref _watchMotorcycle, value);
        }

        private bool _watchBus;
        public bool WatchBus
        {
            get => _watchBus;
            set => SetProperty(ref _watchBus, value);
        }

        private bool _watchTruck;
        public bool WatchTruck
        {
            get => _watchTruck;
            set => SetProperty(ref _watchTruck, value);
        }

        /// <summary>当前勾选的所有监控目标英文类名。</summary>
        public List<string> GetWatchedClasses()
        {
            var list = new List<string>();
            if (WatchPerson) list.Add("person");
            if (WatchBicycle) list.Add("bicycle");
            if (WatchCar) list.Add("car");
            if (WatchMotorcycle) list.Add("motorcycle");
            if (WatchBus) list.Add("bus");
            if (WatchTruck) list.Add("truck");
            return list;
        }

        /// <summary>远控设置监控目标（逗号分隔的类名，空字符串 = 全部）。</summary>
        public void SetWatchedClasses(string csv)
        {
            var set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            if (!string.IsNullOrWhiteSpace(csv))
                foreach (var cls in csv.Split(','))
                {
                    string t = cls.Trim();
                    if (!string.IsNullOrEmpty(t)) set.Add(t);
                }

            bool all = set.Count == 0;
            WatchPerson     = all || set.Contains("person");
            WatchBicycle    = all || set.Contains("bicycle");
            WatchCar        = all || set.Contains("car");
            WatchMotorcycle = all || set.Contains("motorcycle");
            WatchBus        = all || set.Contains("bus");
            WatchTruck      = all || set.Contains("truck");
        }

        /// <summary>模型文件名（yolo26n_320 到 yolo26m_640，6 档）。</summary>
        public string SelectedModelName => SelectedModelIndex switch
        {
            0 => "yolo26n_320",
            1 => "yolo26n_640",
            2 => "yolo26s_320",
            3 => "yolo26s_640",
            4 => "yolo26m_320",
            5 => "yolo26m_640",
            _ => "yolo26n_320"
        };

        public string ThresholdText => $"{Threshold}%";
        public string SamplingRateText => $"{SamplingRate} 次/秒";
        public string CooldownText => $"{Cooldown} 秒";

        // ── 持久化 ───────────────────────────────────────────────────

        public void Load()
        {
            Threshold        = SettingsStore.GetInt("ConfidenceThresholdPct", 45);
            SamplingRate     = SettingsStore.GetInt("TargetFps", 3);
            Cooldown         = SettingsStore.GetInt("AlertCooldownSeconds", 5);
            SelectedModelIndex = SettingsStore.GetInt("SelectedModelIndex", 0);

            var watched = SettingsStore.GetStringList("WatchedClasses");
            WatchPerson     = watched.Contains("person");
            WatchBicycle    = watched.Contains("bicycle");
            WatchCar        = watched.Contains("car");
            WatchMotorcycle = watched.Contains("motorcycle");
            WatchBus        = watched.Contains("bus");
            WatchTruck      = watched.Contains("truck");

            // 兼容旧数据：空集合时默认只选 "person"
            if (watched.Count == 0)
                WatchPerson = true;
        }

        public void Save()
        {
            SettingsStore.Set("ConfidenceThresholdPct", Threshold);
            SettingsStore.Set("TargetFps", SamplingRate);
            SettingsStore.Set("AlertCooldownSeconds", Cooldown);
            SettingsStore.Set("SelectedModelIndex", SelectedModelIndex);

            var watched = GetWatchedClasses();
            SettingsStore.Set("WatchedClasses", string.Join(",", watched));

            SettingsStore.Save();
        }

        public SettingsViewModel()
        {
            PropertyChanged += (s, e) =>
            {
                if (e.PropertyName == nameof(Threshold)) OnPropertyChanged(nameof(ThresholdText));
                if (e.PropertyName == nameof(SamplingRate)) OnPropertyChanged(nameof(SamplingRateText));
                if (e.PropertyName == nameof(Cooldown)) OnPropertyChanged(nameof(CooldownText));
            };
        }
    }
}
