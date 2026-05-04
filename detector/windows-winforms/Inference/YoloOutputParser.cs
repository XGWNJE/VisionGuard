// ┌─────────────────────────────────────────────────────────┐
// │ YoloOutputParser.cs                                     │
// │ 角色：解析 YOLOv5nu ONNX 输出张量为 Detection 列表      │
// │ 线程：在 MonitorService 的 ThreadPool 回调中调用         │
// │ 依赖：CocoClassMap (类名), ImagePreprocessor (ModelSize) │
// │ 对外 API：Parse() — 静态方法                            │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Drawing;
using VisionGuard.Data;
using VisionGuard.Models;

namespace VisionGuard.Inference
{
    /// <summary>
    /// 解析 YOLOv5nu ONNX 输出张量为 Detection 列表。
    ///
    /// 输出格式 [1, 84, 2100]（320px 输入）：
    ///   - 84 = 4(xywh 中心格式, 绝对像素坐标) + 80(class scores)
    ///   - 2100 = 40x40 + 20x20 + 10x10 anchor grid
    ///   - 坐标已是绝对像素值（相对 320x320），无需解码
    ///
    /// 前5置信度顺序匹配：所有候选按置信度降序，取前5名，再 NMS
    /// </summary>
    public static class YoloOutputParser
    {
        private static int ModelSize => ImagePreprocessor.ModelInputSize;

        private static List<string> CocoLabels => CocoClassMap.EnglishNames;

        public static List<Detection> Parse(
            float[]         rawOutput,
            Rectangle       captureRegion,
            float           confThreshold,
            float           iouThreshold,
            HashSet<string> watchedClasses)
        {
            const int numAnchors  = 2100;
            const int numChannels = 84;

            float scaleX = captureRegion.Width  / (float)ModelSize;
            float scaleY = captureRegion.Height / (float)ModelSize;

            var allCandidates = new List<Detection>();

            for (int a = 0; a < numAnchors; a++)
            {
                // 找最高分类分数 (channel 4..83)
                int   bestClass = -1;
                float bestScore = 0f;
                for (int c = 4; c < numChannels; c++)
                {
                    float score = rawOutput[c * numAnchors + a];
                    if (score > bestScore)
                    {
                        bestScore = score;
                        bestClass = c - 4;
                    }
                }

                if (bestScore < confThreshold) continue;

                string label = bestClass < CocoLabels.Count
                    ? CocoLabels[bestClass] : bestClass.ToString();

                if (watchedClasses != null && watchedClasses.Count > 0
                    && !watchedClasses.Contains(label)) continue;

                // 坐标已是绝对像素值（中心格式: cx, cy, w, h）
                float cx = rawOutput[0 * numAnchors + a];
                float cy = rawOutput[1 * numAnchors + a];
                float bw = rawOutput[2 * numAnchors + a];
                float bh = rawOutput[3 * numAnchors + a];

                // 转换为捕获区域内的像素坐标（左上角 + 宽高）
                float x = (cx - bw / 2f) * scaleX;
                float y = (cy - bh / 2f) * scaleY;
                float w = bw * scaleX;
                float h = bh * scaleY;

                allCandidates.Add(new Detection
                {
                    ClassId     = bestClass,
                    Label       = label,
                    Confidence  = bestScore,
                    BoundingBox = new RectangleF(x, y, w, h)
                });
            }

            // 按置信度降序，取前5，再 NMS
            allCandidates.Sort((a, b) => b.Confidence.CompareTo(a.Confidence));
            var top5 = allCandidates.Count > 5
                ? allCandidates.GetRange(0, 5)
                : allCandidates;

            return NMS(top5, iouThreshold);
        }

        private static List<Detection> NMS(List<Detection> dets, float iouThreshold)
        {
            var kept    = new List<Detection>();
            var removed = new bool[dets.Count];

            for (int i = 0; i < dets.Count; i++)
            {
                if (removed[i]) continue;
                kept.Add(dets[i]);
                for (int j = i + 1; j < dets.Count; j++)
                {
                    if (removed[j]) continue;
                    if (dets[i].ClassId == dets[j].ClassId
                        && IoU(dets[i].BoundingBox, dets[j].BoundingBox) > iouThreshold)
                    {
                        removed[j] = true;
                    }
                }
            }
            return kept;
        }

        private static float IoU(RectangleF a, RectangleF b)
        {
            float interX = Math.Max(a.Left, b.Left);
            float interY = Math.Max(a.Top,  b.Top);
            float interW = Math.Min(a.Right, b.Right) - interX;
            float interH = Math.Min(a.Bottom, b.Bottom) - interY;

            if (interW <= 0 || interH <= 0) return 0f;

            float inter = interW * interH;
            float union = a.Width * a.Height + b.Width * b.Height - inter;
            return union <= 0 ? 0f : inter / union;
        }
    }
}
