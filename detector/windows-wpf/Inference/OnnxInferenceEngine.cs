// ┌─────────────────────────────────────────────────────────┐
// │ OnnxInferenceEngine.cs                                  │
// │ 角色：封装 ONNX Runtime 推理会话生命周期                 │
// │ 线程：Run() 线程安全（InferenceSession 内部同步）        │
// │ 依赖：Microsoft.ML.OnnxRuntime NuGet                    │
// │ 对外 API：Run(tensor, shape), Dispose()                 │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using Microsoft.ML.OnnxRuntime;
using Microsoft.ML.OnnxRuntime.Tensors;

namespace VisionGuard.Inference
{
    public enum InferenceBackend
    {
        Cpu,
        DirectML
    }

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

        /// <summary>模型期望的输入尺寸（宽=高）。从 ONNX input shape 提取。</summary>
        public int ModelInputSize { get; }

        /// <summary>实际创建成功并正在执行的后端。</summary>
        public InferenceBackend ActiveBackend { get; }

        /// <summary>DirectML 初始化失败并回退 CPU 时保留的诊断原因。</summary>
        public string BackendFallbackReason { get; } = string.Empty;

        public OnnxInferenceEngine(
            string modelPath,
            int intraOpNumThreads = 2,
            InferenceBackend preferredBackend = InferenceBackend.DirectML,
            int directMlDeviceId = 0)
        {
            if (preferredBackend == InferenceBackend.DirectML)
            {
                try
                {
                    using (var directMlOptions = CreateSessionOptions(intraOpNumThreads, directMl: true, directMlDeviceId))
                    {
                        _session = new InferenceSession(modelPath, directMlOptions);
                    }
                    ActiveBackend = InferenceBackend.DirectML;
                }
                catch (Exception ex)
                {
                    var detail = string.IsNullOrWhiteSpace(ex.Message) ? "运行库未提供详细信息" : ex.Message.Trim();
                    BackendFallbackReason = $"DirectML 设备 {directMlDeviceId} 初始化失败（{ex.GetType().Name}）：{detail}";
                    Debug.WriteLine($"[Inference] DirectML unavailable; falling back to CPU: {ex}");
                }
            }

            if (_session == null)
            {
                using (var cpuOptions = CreateSessionOptions(intraOpNumThreads, directMl: false, directMlDeviceId: 0))
                {
                    _session = new InferenceSession(modelPath, cpuOptions);
                }
                ActiveBackend = InferenceBackend.Cpu;
            }

            _inputName  = _session.InputMetadata.Keys.First();
            _outputName = _session.OutputMetadata.Keys.First();

            var shape = _session.InputMetadata[_inputName].Dimensions;
            ModelInputSize = shape.Length >= 4 ? shape[2] : 320;
        }

        private static SessionOptions CreateSessionOptions(int intraOpNumThreads, bool directMl, int directMlDeviceId)
        {
            var options = new SessionOptions
            {
                IntraOpNumThreads = intraOpNumThreads,
                InterOpNumThreads = 1,
                GraphOptimizationLevel = GraphOptimizationLevel.ORT_ENABLE_ALL,
                ExecutionMode = ExecutionMode.ORT_SEQUENTIAL,
                EnableMemoryPattern = !directMl
            };

            if (directMl)
                options.AppendExecutionProvider_DML(directMlDeviceId);

            return options;
        }

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
                // output0 形状 [1, 300, 6]（YOLO26 已内置 NMS），展平后直接返回
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
