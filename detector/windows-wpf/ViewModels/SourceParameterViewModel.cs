using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Globalization;
using System.Linq;
using VisionGuard.Detector.Windows.Data;
using VisionGuard.Detector.Windows.Utils;

namespace VisionGuard.Detector.Windows.ViewModels
{
    public sealed class SourceParameterViewModel : ViewModelBase
    {
        private readonly SourceViewModel _source;
        private bool _editing, _syncing;
        private string _draft = "", _search = "", _message = "";
        public string Key { get; }
        public string Label { get; }
        public bool IsModel => Key == "modelKey";
        public bool IsTargets => Key == "targets";
        public bool IsNumeric => !IsModel && !IsTargets;
        public bool IsConfidence => Key == "confidence";
        public bool IsSampling => Key == "targetSamplingRate";
        public bool IsCooldown => Key == "cooldown";
        public double NumericMinimum => IsConfidence ? 10 : 1;
        public double NumericMaximum => IsConfidence ? 95 : Key == "cooldown" ? 300 : 5;
        public double NumericDraft { get => double.TryParse(Draft, out var number) ? number : NumericMinimum; set => Draft = Math.Round(value).ToString(CultureInfo.InvariantCulture); }
        public IEnumerable<NumericParameterOption> NumericOptions => Enumerable.Range(1, Key == "cooldown" ? 300 : 5).Select(value => new NumericParameterOption(value.ToString(CultureInfo.InvariantCulture), value + (Key == "cooldown" ? " 秒" : " FPS")));
        public RelayCommand IncreaseCommand { get; }
        public RelayCommand DecreaseCommand { get; }
        public RelayCommand SelectNumericCommand { get; }
        public string[] ModelOptions => _source.ModelOptions;
        public ObservableCollection<DetectionClassOption> TargetOptions { get; } = new();
        public IEnumerable<DetectionClassOption> FilteredTargets => TargetOptions.Where(item => (item.ChineseName + " " + item.EnglishName).IndexOf(Search.Trim(), StringComparison.OrdinalIgnoreCase) >= 0);
        public string Search { get => _search; set { if (SetProperty(ref _search, value)) OnPropertyChanged(nameof(FilteredTargets)); } }
        public string Raw => Key switch { "modelKey" => _source.ModelKey, "targets" => _source.Targets, "confidence" => _source.ThresholdPercent.ToString(), "cooldown" => _source.Cooldown.ToString(), _ => _source.TargetFps.ToString() };
        public string ValueText => IsTargets && ModelManager.IsSupported(_source.ModelKey) ? Raw.Split(',').Length > 3 ? $"{Raw.Split(',').Length} 项 · " + string.Join("、", Raw.Split(',').Take(3).Select(label => CocoClassMap.EnZh.TryGetValue(label, out var title) ? title : label)) + "…" : string.Join("、", Raw.Split(',').Select(label => CocoClassMap.EnZh.TryGetValue(label, out var title) ? title : label)) : Raw;
        public bool CanEdit => _source.CanEdit && (!IsModel || ModelOptions.Length > 0) && (!IsTargets || ModelManager.IsSupported(_source.ModelKey) && ModelManager.IsDownloaded(_source.ModelKey));
        public bool IsEditing { get => _editing; private set => SetProperty(ref _editing, value); }
        public string Draft { get => _draft; set { if (SetProperty(ref _draft, value ?? "")) { OnPropertyChanged(nameof(NumericDraft)); SyncTargets(); Message = ""; SaveCommand?.RaiseCanExecuteChanged(); IncreaseCommand?.RaiseCanExecuteChanged(); DecreaseCommand?.RaiseCanExecuteChanged(); } } }
        public string Message { get => _message; private set => SetProperty(ref _message, value); }
        public string Hint => IsTargets && !CanEdit ? "当前模型不可用或标签未知" : IsNumeric ? Key == "confidence" ? "10–95%，整数" : Key == "cooldown" ? "1–300 秒，整数" : "1–5 FPS，整数" : IsModel && !CanEdit ? "先在全局设置下载模型" : "";
        public RelayCommand EditCommand { get; }
        public RelayCommand RestoreCommand { get; }
        public RelayCommand CancelCommand { get; }
        public RelayCommand SaveCommand { get; }
        internal SourceParameterViewModel(SourceViewModel source, string key, string label)
        {
            _source = source; Key = key; Label = label;
            EditCommand = new RelayCommand(() => { if (!IsEditing) Draft = Raw; IsEditing = true; }, () => CanEdit);
            RestoreCommand = new RelayCommand(() => { Draft = Raw; Message = ""; });
            CancelCommand = new RelayCommand(() => { Draft = Raw; IsEditing = false; Message = ""; });
            SaveCommand = new RelayCommand(Save, () => CanEdit && Valid());
            IncreaseCommand = new RelayCommand(() => NumericDraft++, () => CanEdit && NumericDraft < NumericMaximum);
            DecreaseCommand = new RelayCommand(() => NumericDraft--, () => CanEdit && NumericDraft > NumericMinimum);
            SelectNumericCommand = new RelayCommand(value => Draft = value as string ?? Draft, _ => CanEdit);
            source.PropertyChanged += (s, e) => { OnPropertyChanged(nameof(ValueText)); OnPropertyChanged(nameof(CanEdit)); OnPropertyChanged(nameof(Hint)); OnPropertyChanged(nameof(ModelOptions)); if (e.PropertyName == nameof(SourceViewModel.ModelKey)) BuildTargets(); EditCommand.RaiseCanExecuteChanged(); SaveCommand.RaiseCanExecuteChanged(); IncreaseCommand.RaiseCanExecuteChanged(); DecreaseCommand.RaiseCanExecuteChanged(); };
            BuildTargets();
        }
        private void BuildTargets()
        {
            if (!IsTargets) return;
            TargetOptions.Clear();
            if (ModelManager.IsSupported(_source.ModelKey)) foreach (var name in CocoClassMap.EnglishNames) TargetOptions.Add(new DetectionClassOption(name, CocoClassMap.EnZh[name], ChangedTarget));
            SyncTargets(); OnPropertyChanged(nameof(FilteredTargets));
        }
        private void SyncTargets()
        {
            if (!IsTargets || _syncing) return;
            _syncing = true;
            var selected = Draft.Split(',').Select(item => item.Trim()).ToHashSet(StringComparer.OrdinalIgnoreCase);
            foreach (var item in TargetOptions) item.IsSelected = selected.Contains(item.EnglishName);
            _syncing = false;
        }
        private void ChangedTarget(DetectionClassOption item)
        {
            if (_syncing) return;
            var selected = TargetOptions.Where(option => option.IsSelected).ToArray();
            if (selected.Length == 0) { _syncing = true; item.IsSelected = true; _syncing = false; Message = "至少选择一个目标"; return; }
            Draft = string.Join(",", selected.Select(option => option.EnglishName));
        }
        private bool Valid()
        {
            if (IsModel) return ModelOptions.Contains(Draft);
            if (IsTargets) return Draft.Split(',').Length > 0 && Draft.Split(',').All(label => TargetOptions.Any(option => option.EnglishName == label));
            if (!int.TryParse(Draft, NumberStyles.Integer, CultureInfo.InvariantCulture, out var value)) return false;
            return Key == "confidence" ? value >= 10 && value <= 95 : Key == "cooldown" ? value >= 1 && value <= 300 : value >= 1 && value <= 5;
        }
        private void Save()
        {
            if (!CanEdit || !Valid()) return;
            try { _source.CommitParameter(Key, Draft); IsEditing = false; Message = "已保存"; }
            catch (Exception error) { Message = "保存失败：" + error.Message; }
        }
    }
    public sealed class NumericParameterOption
    {
        public string Value { get; }
        public string Label { get; }
        public NumericParameterOption(string value, string label) { Value = value; Label = label; }
    }
}
