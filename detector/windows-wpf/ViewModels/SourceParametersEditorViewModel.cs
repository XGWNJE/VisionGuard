using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Globalization;
using System.Linq;
using VisionGuard.Detector.Windows.Data;
using VisionGuard.Detector.Windows.Utils;

namespace VisionGuard.Detector.Windows.ViewModels
{
    public sealed class SourceParametersEditorViewModel : ViewModelBase, IDisposable
    {
        private readonly SourceViewModel _source;
        private string _model = "", _search = "", _message = "";
        private int _confidence, _fps, _cooldown;
        private string _baseline = "";
        private bool _restoring;
        public string SourceName => _source.SourceName;
        public string StatusText => _source.StatusText;
        public string ModelKey { get => _model; set { if (SetProperty(ref _model, value)) Refresh(); } }
        public int Confidence { get => _confidence; set { if (SetProperty(ref _confidence, Math.Max(10, Math.Min(95, value)))) Refresh(); } }
        public int Fps { get => _fps; set { if (SetProperty(ref _fps, Math.Max(1, Math.Min(5, value)))) Refresh(); } }
        public int Cooldown { get => _cooldown; set { if (SetProperty(ref _cooldown, Math.Max(1, Math.Min(300, value)))) Refresh(); } }
        public string Search { get => _search; set { if (SetProperty(ref _search, value ?? "")) OnPropertyChanged(nameof(FilteredTargets)); } }
        public string Message { get => _message; private set => SetProperty(ref _message, value); }
        public bool CanEdit => _source.CanEdit;
        public bool ModelAvailable => ModelManager.IsSupported(ModelKey) && ModelManager.IsDownloaded(ModelKey);
        public bool SourceChanged => _baseline != SourceSnapshot();
        public string EditHint => !CanEdit ? "当前来源运行中，请先停止后调整。" : SourceChanged ? "来源配置已变化，请还原为当前值后重新调整。" : !ModelAvailable ? "当前模型不可用，请在全局设置下载或选择可用模型。" : "保存后应用到当前来源；取消保留原配置。";
        public bool CanSave => CanEdit && !SourceChanged && ModelAvailable && ModelOptions.Any(x => x.Key == ModelKey) && Targets.Any(x => x.IsSelected) && DraftSnapshot() != _baseline;
        public bool IsCustomCooldown => !new[] { 5, 10, 30, 60 }.Contains(Cooldown);
        public IReadOnlyList<ModelParameterOption> ModelOptions => _source.ModelOptions.Select(key => new ModelParameterOption(key, ModelLabel(key))).ToArray();
        public IReadOnlyList<DetectionClassOption> Targets { get; }
        public IEnumerable<DetectionClassOption> CommonTargets => Targets.Where(x => x.EnglishName == "person" || x.EnglishName == "car");
        public IEnumerable<DetectionClassOption> FilteredTargets => Targets.Where(x => (x.ChineseName + " " + x.EnglishName).IndexOf(Search.Trim(), StringComparison.OrdinalIgnoreCase) >= 0);
        public string TargetsSummary => string.Join("、", Targets.Where(x => x.IsSelected).Select(x => x.ChineseName));
        public IReadOnlyList<ParameterChoice> FpsOptions { get; }
        public IReadOnlyList<ParameterChoice> CooldownOptions { get; }
        public RelayCommand DecreaseConfidence { get; }
        public RelayCommand IncreaseConfidence { get; }
        public RelayCommand DecreaseCooldown { get; }
        public RelayCommand IncreaseCooldown { get; }
        public RelayCommand RestoreCommand { get; }

        public SourceParametersEditorViewModel(SourceViewModel source)
        {
            _source = source;
            Targets = CocoClassMap.EnglishNames.Select(key => new DetectionClassOption(key, CocoClassMap.EnZh[key], TargetChanged)).ToArray();
            FpsOptions = Enumerable.Range(1, 5).Select(value => new ParameterChoice(value, value.ToString(CultureInfo.InvariantCulture), () => Fps = value)).ToArray();
            CooldownOptions = new[] { 5, 10, 30, 60 }.Select(value => new ParameterChoice(value, value + " 秒", () => Cooldown = value)).ToArray();
            DecreaseConfidence = new RelayCommand(() => Confidence--, () => CanEdit && Confidence > 10);
            IncreaseConfidence = new RelayCommand(() => Confidence++, () => CanEdit && Confidence < 95);
            DecreaseCooldown = new RelayCommand(() => Cooldown--, () => CanEdit && Cooldown > 1);
            IncreaseCooldown = new RelayCommand(() => Cooldown++, () => CanEdit && Cooldown < 300);
            RestoreCommand = new RelayCommand(Restore);
            Restore();
            source.PropertyChanged += SourceChangedHandler;
        }
        public static string ModelLabel(string key)
        {
            int index = Array.IndexOf(ModelManager.ModelKeys, key);
            return index < 0 ? key : ModelManager.ModelDisplayNames[index].Split('(')[0].Trim();
        }
        private string SourceSnapshot() => Snapshot(_source.ModelKey, _source.Targets, _source.ThresholdPercent, _source.TargetFps, _source.Cooldown);
        private string DraftSnapshot() => Snapshot(ModelKey, string.Join(",", Targets.Where(x => x.IsSelected).Select(x => x.EnglishName)), Confidence, Fps, Cooldown);
        private static string Snapshot(string model, string targets, int confidence, int fps, int cooldown) => string.Join("|", model, string.Join(",", targets.Split(',').OrderBy(x => x, StringComparer.Ordinal)), confidence, fps, cooldown);
        private void Restore()
        {
            _restoring = true;
            ModelKey = _source.ModelKey; Confidence = _source.ThresholdPercent; Fps = _source.TargetFps; Cooldown = _source.Cooldown;
            var selected = _source.Targets.Split(',');
            foreach (var target in Targets) target.IsSelected = selected.Contains(target.EnglishName);
            _baseline = SourceSnapshot();
            _restoring = false; Message = ""; Refresh();
        }
        private void TargetChanged(DetectionClassOption target)
        {
            if (_restoring) return;
            if (!Targets.Any(x => x.IsSelected)) { _restoring = true; target.IsSelected = true; _restoring = false; Message = "至少选择一个检测目标。"; }
            else Message = "";
            Refresh();
        }
        private void SourceChangedHandler(object? sender, PropertyChangedEventArgs e)
        {
            if (e.PropertyName == nameof(SourceViewModel.ModelOptions)) OnPropertyChanged(nameof(ModelOptions));
            if (new[] { nameof(SourceViewModel.ModelOptions), nameof(SourceViewModel.ModelKey), nameof(SourceViewModel.Targets), nameof(SourceViewModel.ThresholdPercent), nameof(SourceViewModel.TargetFps), nameof(SourceViewModel.Cooldown), nameof(SourceViewModel.CanEdit), nameof(SourceViewModel.SourceName), nameof(SourceViewModel.StatusText) }.Contains(e.PropertyName)) Refresh();
        }
        private void Refresh()
        {
            if (_restoring) return;
            foreach (var choice in FpsOptions) choice.IsSelected = choice.Value == Fps;
            foreach (var choice in CooldownOptions) choice.IsSelected = choice.Value == Cooldown;
            foreach (var name in new[] { nameof(SourceName), nameof(StatusText), nameof(CanEdit), nameof(ModelAvailable), nameof(SourceChanged), nameof(EditHint), nameof(CanSave), nameof(TargetsSummary), nameof(IsCustomCooldown) }) OnPropertyChanged(name);
            DecreaseConfidence?.RaiseCanExecuteChanged(); IncreaseConfidence?.RaiseCanExecuteChanged();
            DecreaseCooldown?.RaiseCanExecuteChanged(); IncreaseCooldown?.RaiseCanExecuteChanged();
        }
        public bool TrySave()
        {
            if (!CanSave) { Message = EditHint; return false; }
            try { _source.CommitParameters(ModelKey, string.Join(",", Targets.Where(x => x.IsSelected).Select(x => x.EnglishName)), Confidence, Fps, Cooldown); _baseline = SourceSnapshot(); return true; }
            catch (Exception error) { Message = "保存失败：" + error.Message; Refresh(); return false; }
        }
        public void Dispose() => _source.PropertyChanged -= SourceChangedHandler;
    }
    public sealed class ModelParameterOption
    {
        public string Key { get; }
        public string Label { get; }
        public ModelParameterOption(string key, string label) { Key = key; Label = label; }
    }
    public sealed class ParameterChoice : ViewModelBase
    {
        private bool _selected;
        public int Value { get; }
        public string Label { get; }
        public bool IsSelected { get => _selected; internal set => SetProperty(ref _selected, value); }
        public RelayCommand SelectCommand { get; }
        public ParameterChoice(int value, string label, Action select) { Value = value; Label = label; SelectCommand = new RelayCommand(select); }
    }
}
