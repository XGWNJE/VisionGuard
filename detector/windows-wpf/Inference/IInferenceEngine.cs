using System;

namespace VisionGuard.Detector.Windows.Inference
{
    public interface IInferenceEngine : IDisposable
    {
        int ModelInputSize { get; }
        InferenceBackend ActiveBackend { get; }
        string BackendFallbackReason { get; }
        float[] Run(float[] inputData, int[] shape);
    }
}
