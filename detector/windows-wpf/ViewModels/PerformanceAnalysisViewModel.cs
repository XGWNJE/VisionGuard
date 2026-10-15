using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using VisionGuard.Detector.Windows.Services;
using VisionGuard.Detector.Windows.Utils;

namespace VisionGuard.Detector.Windows.ViewModels
{
    public sealed class PerformanceAnalysisViewModel : ViewModelBase
    {
        private readonly object _sync = new();
        private readonly List<SourcePerformanceSample> _samples = new();
        private bool _collecting;
        private string _status = "尚未检测";
        private string _summary = "仅统计本机真实处理；发送入队耗时不代表网络往返或远端处理耗时。";
        public string Status { get => _status; private set => SetProperty(ref _status, value); }
        public string Summary { get => _summary; private set => SetProperty(ref _summary, value); }
        public ObservableCollection<PerformanceResult> Results { get; } = new();
        public RelayCommand StartCommand { get; }
        public PerformanceAnalysisViewModel() => StartCommand = new RelayCommand(async () => await Run(), () => !_collecting);

        public void Record(SourcePerformanceSample sample)
        {
            lock (_sync) if (_collecting && _samples.Count < 4096 && sample.Stages.Count > 0) _samples.Add(sample);
        }

        private async Task Run()
        {
            lock (_sync) { if (_collecting) return; _samples.Clear(); _collecting = true; }
            StartCommand.RaiseCanExecuteChanged();
            Results.Clear();
            for (int left = 10; left > 0; left--) { Status = $"正在采样，还剩 {left} 秒"; await Task.Delay(1000); }
            SourcePerformanceSample[] samples;
            lock (_sync) { _collecting = false; samples = _samples.ToArray(); _samples.Clear(); }
            foreach (var source in samples.GroupBy(sample => sample.SourceId)) Results.Add(PerformanceMeasurement.Analyze(source.ToArray()));
            Status = samples.Length == 0 ? "没有采到处理帧；请先启动来源，并确认相机 / 窗口 / 远程画面可用。" : $"检测完成：{Results.Count} 路来源，{samples.Length} 个真实处理样本";
            StartCommand.RaiseCanExecuteChanged();
        }
    }
}
