// ┌─────────────────────────────────────────────────────────┐
// │ YoloOutputParser.cs                                     │
// │ 角色：解析两档 YOLO ONNX 输出张量为 Detection 列表       │
// │ 线程：在 MonitorService 的 ThreadPool 回调中调用         │
// │ 依赖：CocoClassMap (类名), ImagePreprocessor (ModelSize) │
// │ 对外 API：Detect(), Parse() — 静态方法                  │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Drawing;
using VisionGuard.Data;
using VisionGuard.Models;

namespace VisionGuard.Inference
{
    /// <summary>
    /// 解析 YOLO ONNX 输出张量为 Detection 列表。
    ///
    /// 两个档位的输出契约不同，必须按输出张量形态分档解析，不能共用一套下标：
    ///
    /// 1) YOLO26（modern 档，Win10/11）：`[1, 300, 6]`，展平 1800 个 float
    ///    - 300 = 最多保留 300 个检测框，6 = [x1, y1, x2, y2, confidence, class_id]
    ///    - 坐标是相对输入尺寸（320/640）的绝对像素，模型内部已完成 NMS
    ///
    /// 2) YOLOv5u（legacy 档，Win7 SP1）：`[1, 84, N]`，展平 84*N 个 float
    ///    - 84 = 4(cx, cy, w, h 绝对像素) + 80(class score)，N = 各尺度锚点总数
    ///      （320 输入 2100 = 40²+20²+10²，640 输入 8400 = 80²+40²+20²）
    ///    - 通道优先存储：第 c 通道第 a 个锚点的下标是 c * N + a
    ///    - 类分数是 argmax 得到的分数本身（无独立 objectness），
    ///      模型内部**没有** NMS，必须在解析侧按类做 NMS
    ///
    /// 形态判定只依赖输出长度，不依赖运行环境探测：两者互不整除，判定唯一。
    /// 形态不认识时抛异常（错误会以“推理”故障显示在对应来源），
    /// 绝不按某一档的下标去读另一档的张量——那会产生完全偏离的画面内容且静默不出框。
    ///
    /// 两档统一返回最多 5 个检测框（按置信度降序）。
    /// </summary>
    public static class YoloOutputParser
    {
        /// <summary>YOLO26 端到端输出：[1, 300, 6]。</summary>
        private const int Yolo26Detections = 300;
        private const int Yolo26ValuesPerBox = 6;
        /// <summary>[x1, y1, x2, y2, confidence, class_id] 中置信度的列下标。</summary>
        private const int Yolo26ConfidenceIndex = 4;

        /// <summary>YOLOv5u [1, 84, N] 的通道数：4 个框参数 + 80 个 COCO 类分数。</summary>
        private const int YoloV5Channels = 84;
        private const int YoloV5BoxChannels = 4;
        /// <summary>YOLOv5 三尺度锚点总数（320 输入：40² + 20² + 10²）。</summary>
        private const int YoloV5AnchorsAt320 = 2100;
        /// <summary>YOLOv5 三尺度锚点总数（640 输入：80² + 40² + 20²）。</summary>
        private const int YoloV5AnchorsAt640 = 8400;

        /// <summary>NMS 的 IoU 阈值。YOLOv5 模型图内没有 NMS，必须在这里补。</summary>
        private const float NmsIouThreshold = 0.45f;
        /// <summary>送入 NMS 的候选上限，避免极端画面下退化成全量两两比较。</summary>
        private const int MaxCandidatesForNms = 3000;
        /// <summary>输出给 UI 与报警的最大检测数，两档一致。</summary>
        private const int MaxDetections = 5;

        // COCO 80 类名：引用 CocoClassMap 消除重复
        private static List<string> CocoLabels => CocoClassMap.EnglishNames;

        /// <summary>输出张量形态，用于解析分支与诊断日志。</summary>
        public enum OutputLayout
        {
            /// <summary>YOLO26 端到端 [1, 300, 6]（已内置 NMS）。</summary>
            Yolo26EndToEnd,
            /// <summary>YOLOv5u [1, 84, N]（原始锚点输出，需解析侧 NMS）。</summary>
            YoloV5Anchors,
        }

        /// <summary>按输出长度推断形态；长度不符合任一档契约时抛异常。</summary>
        public static OutputLayout Detect(int rawLength)
        {
            if (rawLength == Yolo26Detections * Yolo26ValuesPerBox) return OutputLayout.Yolo26EndToEnd;

            if (rawLength > 0 && rawLength % YoloV5Channels == 0)
            {
                int anchors = rawLength / YoloV5Channels;
                // 锚点数必须等于某个受支持输入尺寸的三尺度网格之和：
                //   320 输入 → 40² + 20² + 10² = 2100，640 输入 → 80² + 40² + 20² = 8400。
                // 不能只按「84 整除」通过：其他长度同样可能是 84 的倍数，
                // 放行后就是又一次静默漏检。
                if (anchors == YoloV5AnchorsAt320 || anchors == YoloV5AnchorsAt640)
                    return OutputLayout.YoloV5Anchors;
            }

            throw new InvalidOperationException(
                $"无法识别的模型输出张量：展平长度 {rawLength} 既不匹配 YOLO26 的 [1,300,6]，也不匹配 YOLOv5 的 [1,84,N]。" +
                "请确认所选模型与本机推理档位一致（Win7 legacy 档只能用 yolov5*，Win10+ modern 档只能用 yolo26*）。");
        }

        /// <summary>输出张量的可读形态文本，用于诊断日志与故障提示。</summary>
        public static string Describe(int rawLength)
        {
            switch (Detect(rawLength))
            {
                case OutputLayout.Yolo26EndToEnd: return $"[1,{Yolo26Detections},{Yolo26ValuesPerBox}]";
                default:                          return $"[1,{YoloV5Channels},{rawLength / YoloV5Channels}]";
            }
        }

        /// <summary>解析分支名称：说明该形态由哪条解析路径处理、NMS 来自哪里。</summary>
        public static string DescribeBranch(int rawLength)
            => Detect(rawLength) == OutputLayout.Yolo26EndToEnd
                ? "YOLO26 端到端(模型内置 NMS)"
                : "YOLOv5 锚点(解析侧 NMS)";

        /// <summary>
        /// 解析 ONNX 原始输出，返回过滤后的 Detection 列表。
        /// </summary>
        /// <param name="rawOutput">Run() 返回的展平 float[]</param>
        /// <param name="captureRegion">原始捕获区域（用于把模型输入坐标映射回画布像素）</param>
        /// <param name="confThreshold">置信度阈值</param>
        /// <param name="watchedClasses">只保留这些类名（null 或空集合 = 全部）</param>
        /// <param name="modelSize">模型输入尺寸（宽=高）</param>
        public static List<Detection> Parse(
            float[]         rawOutput,
            Rectangle       captureRegion,
            float           confThreshold,
            HashSet<string> watchedClasses,
            int             modelSize)
        {
            if (rawOutput == null) throw new ArgumentNullException(nameof(rawOutput));

            float scaleX = captureRegion.Width  / (float)modelSize;
            float scaleY = captureRegion.Height / (float)modelSize;

            return Detect(rawOutput.Length) == OutputLayout.Yolo26EndToEnd
                ? ParseYolo26(rawOutput, scaleX, scaleY, confThreshold, watchedClasses)
                : ParseYoloV5(rawOutput, scaleX, scaleY, confThreshold, watchedClasses);
        }

        // ── YOLO26 [1,300,6]：模型已内置 NMS，只做阈值/类别过滤 ──────────────

        private static List<Detection> ParseYolo26(
            float[] rawOutput, float scaleX, float scaleY, float confThreshold, HashSet<string> watchedClasses)
        {
            var candidates = new List<Detection>();

            for (int i = 0; i < Yolo26Detections; i++)
            {
                int idx = i * Yolo26ValuesPerBox;
                float conf = rawOutput[idx + Yolo26ConfidenceIndex];
                if (conf < confThreshold) continue;

                int cls = (int)rawOutput[idx + 5];
                if (cls < 0 || cls >= CocoLabels.Count) continue;

                string label = CocoLabels[cls];
                if (!IsWatched(label, watchedClasses)) continue;

                float x1 = rawOutput[idx + 0];
                float y1 = rawOutput[idx + 1];
                float x2 = rawOutput[idx + 2];
                float y2 = rawOutput[idx + 3];

                candidates.Add(new Detection
                {
                    ClassId     = cls,
                    Label       = label,
                    Confidence  = conf,
                    BoundingBox = new RectangleF(x1 * scaleX, y1 * scaleY,
                                                 (x2 - x1) * scaleX, (y2 - y1) * scaleY)
                });
            }

            return TopByConfidence(candidates, MaxDetections);
        }

        // ── YOLOv5u [1,84,N]：通道优先原始锚点，需要类内 NMS ─────────────────

        private static List<Detection> ParseYoloV5(
            float[] rawOutput, float scaleX, float scaleY, float confThreshold, HashSet<string> watchedClasses)
        {
            int anchors = rawOutput.Length / YoloV5Channels;
            int classCount = Math.Min(80, CocoLabels.Count);
            var candidates = new List<Detection>();

            for (int a = 0; a < anchors; a++)
            {
                // 置信度取 80 个类分数中的最大值（YOLOv5 无独立 objectness）
                int   bestClass = -1;
                float bestScore = confThreshold;
                for (int c = 0; c < classCount; c++)
                {
                    float score = rawOutput[(YoloV5BoxChannels + c) * anchors + a];
                    if (score > bestScore)
                    {
                        bestScore = score;
                        bestClass = c;
                    }
                }

                if (bestClass < 0) continue;

                string label = CocoLabels[bestClass];
                if (!IsWatched(label, watchedClasses)) continue;

                // 坐标已是相对模型输入的绝对像素，中心点格式
                float cx = rawOutput[0 * anchors + a];
                float cy = rawOutput[1 * anchors + a];
                float bw = rawOutput[2 * anchors + a];
                float bh = rawOutput[3 * anchors + a];
                if (bw <= 0f || bh <= 0f) continue;

                candidates.Add(new Detection
                {
                    ClassId     = bestClass,
                    Label       = label,
                    Confidence  = bestScore,
                    BoundingBox = new RectangleF((cx - bw / 2f) * scaleX, (cy - bh / 2f) * scaleY,
                                                 bw * scaleX, bh * scaleY)
                });
            }

            // 先按置信度截断候选，再按类做 NMS，最后取前 5。
            // 顺序很关键：若先取前 5 再 NMS，同一目标的重复框会把名额吃光，剩下的真目标被丢掉。
            var pooled = TopByConfidence(candidates, MaxCandidatesForNms);
            return TopByConfidence(NonMaximumSuppression(pooled, NmsIouThreshold), MaxDetections);
        }

        // ── 公共后处理 ──────────────────────────────────────────────────────

        private static bool IsWatched(string label, HashSet<string> watchedClasses)
            => watchedClasses == null || watchedClasses.Count == 0 || watchedClasses.Contains(label);

        private static List<Detection> TopByConfidence(List<Detection> detections, int limit)
        {
            detections.Sort((a, b) => b.Confidence.CompareTo(a.Confidence));
            return detections.Count > limit ? detections.GetRange(0, limit) : detections;
        }

        /// <summary>逐类 NMS：保留置信度更高的框，抑制同类重叠框。</summary>
        private static List<Detection> NonMaximumSuppression(List<Detection> detections, float iouThreshold)
        {
            var kept = new List<Detection>();
            var suppressed = new bool[detections.Count];

            for (int i = 0; i < detections.Count; i++)
            {
                if (suppressed[i]) continue;
                kept.Add(detections[i]);

                for (int j = i + 1; j < detections.Count; j++)
                {
                    if (suppressed[j] || detections[j].ClassId != detections[i].ClassId) continue;
                    if (IntersectionOverUnion(detections[i].BoundingBox, detections[j].BoundingBox) > iouThreshold)
                        suppressed[j] = true;
                }
            }

            return kept;
        }

        private static float IntersectionOverUnion(RectangleF a, RectangleF b)
        {
            float interWidth  = Math.Min(a.Right, b.Right) - Math.Max(a.Left, b.Left);
            float interHeight = Math.Min(a.Bottom, b.Bottom) - Math.Max(a.Top, b.Top);
            if (interWidth <= 0f || interHeight <= 0f) return 0f;

            float intersection = interWidth * interHeight;
            float union = a.Width * a.Height + b.Width * b.Height - intersection;
            return union <= 0f ? 0f : intersection / union;
        }
    }
}
