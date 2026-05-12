// ┌─────────────────────────────────────────────────────────┐
// │ OnnxInferenceEngine.cs                                  │
// │ 角色：封装 ONNX Runtime 推理会话生命周期                 │
// │ 线程：Run() 线程安全（InferenceSession 内部同步）        │
// │ 依赖：Microsoft.ML.OnnxRuntime NuGet                    │
// │ 对外 API：Run(tensor, shape), Dispose()                 │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.ML.OnnxRuntime;
using Microsoft.ML.OnnxRuntime.Tensors;

namespace VisionGuard.Inference
{
    /// <summary>
    /// 封装 ONNX Runtime InferenceSession 生命周期。
    /// 线程安全：每次 Run 是无状态的，但 InferenceSession 本身线程安全。
    /// </summary>
    public sealed class OnnxInferenceEngine : IDisposable
    {
        private InferenceSession _session;
        private readonly string  _inputName;
        private readonly string  _outputName;
        private bool _disposed;

        /// <summary>模型输入尺寸（正方形，320 或 640）</summary>
        public int ModelInputSize { get; }

        /// <summary>输出张量展平总元素数（= 84 * numAnchors）</summary>
        public int OutputLength { get; }

        public OnnxInferenceEngine(string modelPath, int intraOpNumThreads = 2)
        {
            var opts = new SessionOptions();
            opts.IntraOpNumThreads     = intraOpNumThreads;
            opts.InterOpNumThreads     = 1;
            opts.GraphOptimizationLevel = GraphOptimizationLevel.ORT_ENABLE_ALL;
            opts.ExecutionMode          = ExecutionMode.ORT_SEQUENTIAL;

            _session    = new InferenceSession(modelPath, opts);
            _inputName  = _session.InputMetadata.Keys.First();
            _outputName = _session.OutputMetadata.Keys.First();

            // 从 ONNX 模型元数据读取输入尺寸（取 H 维度，-1 时回退文件名解析）
            int[] inDims = _session.InputMetadata[_inputName].Dimensions;
            int h = inDims.Length >= 4 ? inDims[2] : -1;
            if (h <= 0)
            {
                // 动态维度回退：从文件名提取尺寸（如 yolov5n_320.onnx → 320）
                string name = System.IO.Path.GetFileNameWithoutExtension(modelPath);
                var parts = name.Split('_');
                if (parts.Length >= 2 && int.TryParse(parts[parts.Length - 1], out int parsed))
                    h = parsed;
                else
                    h = 320; // 最终默认
            }
            ModelInputSize = h;

            // 输出展平长度 = 84 * numAnchors（numAnchors 随输入尺寸变化）
            int[] outDims = _session.OutputMetadata[_outputName].Dimensions;
            int total = 1;
            foreach (int d in outDims) total *= (d > 0 ? d : 1);
            OutputLength = total > 0 ? total : 84 * NumAnchors(ModelInputSize);
        }

        /// <summary>根据输入尺寸计算 anchor 网格点数。</summary>
        public static int NumAnchors(int modelSize) =>
            (modelSize / 8) * (modelSize / 8)
          + (modelSize / 16) * (modelSize / 16)
          + (modelSize / 32) * (modelSize / 32);

        /// <summary>
        /// 运行推理，返回原始 float 数组（output0 展平）。
        /// </summary>
        public float[] Run(float[] inputData, int[] shape)
        {
            if (_disposed) throw new ObjectDisposedException(nameof(OnnxInferenceEngine));

            // DenseTensor<T>(Memory<T>, ReadOnlySpan<int>) — shape 必须是 int[]，不是 long[]
            var tensor = new DenseTensor<float>(inputData, shape);

            var inputs = new List<NamedOnnxValue>
            {
                NamedOnnxValue.CreateFromTensor(_inputName, tensor)
            };

            using (IDisposableReadOnlyCollection<DisposableNamedOnnxValue> outputs = _session.Run(inputs))
            {
                // output0 形状 [1, 84, 2100]（YOLOv5nu 输出），展平后直接返回
                // 1.1.0 的 IDisposableReadOnlyCollection 无索引器，用 First()
                var outTensor = outputs.First().AsTensor<float>();
                return outTensor.ToArray();
            }
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            _session?.Dispose();
            _session = null;
        }
    }
}
