using System;
using System.Collections.Generic;
using System.Drawing;
using VisionGuard.Models;

namespace VisionGuard.Inference
{
    /// <summary>
    /// 解析 YOLOv5nu ONNX 输出张量为 Detection 列表。
    ///
    /// 输出格式 [1, 84, 2100]（与旧版 YOLOv5 不同）：
    ///   - 84 = 4(xywh) + 80(class scores)，无 objectness 列
    ///   - 2100 = 40x40 + 20x20 + 10x10 anchor grid（320px 输入）
    ///   - 坐标已是绝对像素值（相对 320x320），无需乘以 anchors
    /// </summary>
    public static class YoloOutputParser
    {
        private const int ModelSize = 320;

        // COCO 80 类名
        private static readonly string[] CocoLabels =
        {
            "person","bicycle","car","motorcycle","airplane","bus","train","truck","boat",
            "traffic light","fire hydrant","stop sign","parking meter","bench","bird","cat",
            "dog","horse","sheep","cow","elephant","bear","zebra","giraffe","backpack",
            "umbrella","handbag","tie","suitcase","frisbee","skis","snowboard","sports ball",
            "kite","baseball bat","baseball glove","skateboard","surfboard","tennis racket",
            "bottle","wine glass","cup","fork","knife","spoon","bowl","banana","apple",
            "sandwich","orange","broccoli","carrot","hot dog","pizza","donut","cake","chair",
            "couch","potted plant","bed","dining table","toilet","tv","laptop","mouse",
            "remote","keyboard","cell phone","microwave","oven","toaster","sink",
            "refrigerator","book","clock","vase","scissors","teddy bear","hair drier",
            "toothbrush"
        };

        /// <summary>
        /// 解析 ONNX 原始输出，返回过滤后并经 NMS 的 Detection 列表。
        /// </summary>
        /// <param name="rawOutput">Run() 返回的展平 float[]，长度 = 84 * 2100</param>
        /// <param name="captureRegion">原始捕获区域（用于将坐标映射回屏幕）</param>
        /// <param name="confThreshold">置信度阈值</param>
        /// <param name="iouThreshold">NMS IoU 阈值</param>
        /// <param name="watchedClassIds">只保留这些类（null 或空集 = 全部）</param>
        public static List<Detection> Parse(
            float[]      rawOutput,
            Rectangle    captureRegion,
            float        confThreshold,
            float        iouThreshold,
            HashSet<int> watchedClassIds)
        {
            // rawOutput 展平自 [1, 84, 2100]
            // 索引: rawOutput[channel * 2100 + anchor]
            const int numAnchors  = 2100;
            const int numChannels = 84; // 4 + 80

            float scaleX = captureRegion.Width  / (float)ModelSize;
            float scaleY = captureRegion.Height / (float)ModelSize;

            var candidates = new List<Detection>();

            for (int a = 0; a < numAnchors; a++)
            {
                // 找最高分类分数
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
                if (watchedClassIds != null && watchedClassIds.Count > 0
                    && !watchedClassIds.Contains(bestClass)) continue;

                float cx = rawOutput[0 * numAnchors + a];
                float cy = rawOutput[1 * numAnchors + a];
                float bw = rawOutput[2 * numAnchors + a];
                float bh = rawOutput[3 * numAnchors + a];

                // 转换为捕获区域内的像素坐标
                float x = (cx - bw / 2f) * scaleX;
                float y = (cy - bh / 2f) * scaleY;
                float w = bw * scaleX;
                float h = bh * scaleY;

                candidates.Add(new Detection
                {
                    ClassId    = bestClass,
                    Label      = bestClass < CocoLabels.Length ? CocoLabels[bestClass] : bestClass.ToString(),
                    Confidence = bestScore,
                    BoundingBox = new RectangleF(x, y, w, h)
                });
            }

            return NMS(candidates, iouThreshold);
        }

        // ── NMS ─────────────────────────────────────────────────────

        private static List<Detection> NMS(List<Detection> dets, float iouThreshold)
        {
            // 按置信度降序
            dets.Sort((a, b) => b.Confidence.CompareTo(a.Confidence));

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
