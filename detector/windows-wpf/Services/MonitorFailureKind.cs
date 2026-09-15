namespace VisionGuard.Services
{
    /// <summary>
    /// 逐来源的故障分类，随帧结果上报到对应来源。
    /// 当前不存在会触发全局停机的故障类型：所有故障都只影响发生故障的那一路。
    /// </summary>
    public enum MonitorFailureKind
    {
        None,
        Capture,
        BlackFrame,
        Inference,
        Processing,
    }
}
