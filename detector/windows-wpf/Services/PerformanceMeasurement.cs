using System;
using System.Collections.Generic;
using System.Linq;

namespace VisionGuard.Detector.Windows.Services
{
    public sealed class SourcePerformanceSample : EventArgs
    {
        public string SourceId { get; }
        public string SourceName { get; }
        public string Backend { get; }
        public int TargetFps { get; }
        public DateTime At { get; } = DateTime.UtcNow;
        public IReadOnlyDictionary<string, long> Stages { get; }
        public SourcePerformanceSample(string id, string name, string backend, int target, IReadOnlyDictionary<string, long> stages)
            => (SourceId, SourceName, Backend, TargetFps, Stages) = (id, name, backend, target, stages);
    }

    public sealed class PerformanceResult
    {
        public string Source { get; init; } = "";
        public string Detail { get; init; } = "";
        public string Stages { get; init; } = "";
        public string Bottleneck { get; init; } = "";
    }

    public static class PerformanceMeasurement
    {
        private static readonly Dictionary<string, string> Labels = new()
        {
            ["captureMs"] = "采集 / 解码", ["maskMs"] = "遮罩", ["preprocessMs"] = "预处理",
            ["inferMs"] = "模型推理", ["parseMs"] = "结果解析", ["alertMs"] = "告警处理",
            ["encodeMs"] = "缩放 / 编码", ["sendMs"] = "发送入队",
        };

        public static PerformanceResult Analyze(IReadOnlyList<SourcePerformanceSample> samples)
        {
            if (samples.Count == 0) return new PerformanceResult { Detail = "没有有效样本" };
            var first = samples[0];
            var stages = Labels.Keys.Where(key => samples.Any(sample => sample.Stages.ContainsKey(key)))
                .Select(key => new { Key = key, Values = samples.Where(sample => sample.Stages.ContainsKey(key)).Select(sample => sample.Stages[key]).OrderBy(value => value).ToArray() })
                .Select(stage => new { stage.Key, Mean = stage.Values.Average(), P95 = stage.Values[(int)Math.Ceiling(stage.Values.Length * .95) - 1], Max = stage.Values.Last() }).ToArray();
            var slowest = stages.OrderByDescending(stage => stage.Mean).FirstOrDefault();
            double total = samples.Where(sample => sample.Stages.ContainsKey("processMs")).Select(sample => (double)sample.Stages["processMs"]).DefaultIfEmpty(0).Average();
            double span = (samples.Last().At - first.At).TotalSeconds;
            double fps = samples.Count < 2 || span <= 0 ? 0 : (samples.Count - 1) / span;
            string bottleneck = slowest == null || slowest.Mean <= 0 ? "计时精度内无明显耗时项" : Labels[slowest.Key];
            return new PerformanceResult {
                Source = first.SourceName,
                Bottleneck = bottleneck,
                Detail = $"{first.Backend} · {samples.Count} 帧 · 实际 {fps:0.0} / 目标 {first.TargetFps} FPS · 平均处理 {total:0.0} ms / 帧预算 {1000d / Math.Max(1, first.TargetFps):0.0} ms。主要耗时：{bottleneck}。" +
                    (fps > 0 && fps < first.TargetFps * .8 && total < 800d / Math.Max(1, first.TargetFps) ? "处理耗时有余量，需检查画面供应、网络等待或调度。" : ""),
                Stages = "环节：平均 / P95 / 最大（ms）\n" + string.Join("\n", stages.Select(stage => $"{Labels[stage.Key]}：{stage.Mean:0.0} / {stage.P95} / {stage.Max}")),
            };
        }
    }
}
