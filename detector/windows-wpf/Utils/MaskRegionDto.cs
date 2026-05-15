// ┌─────────────────────────────────────────────────────────┐
// │ MaskRegionDto.cs                                        │
// │ 角色：遮罩区域持久化 DTO（与旧版 settings.ini 兼容）     │
// │ 格式：JSON 数组 [{left,top,right,bottom},...]           │
// └─────────────────────────────────────────────────────────┘
namespace VisionGuard.Utils
{
    /// <summary>
    /// 遮罩区域序列化 DTO，与旧版 VisionGuard 的 settings.ini 格式完全兼容。
    /// 坐标为相对值 [0,1]，存储为 left/top/right/bottom 而非 X/Y/Width/Height。
    /// </summary>
    internal class MaskRegionDto
    {
        public float left   { get; set; }
        public float top    { get; set; }
        public float right  { get; set; }
        public float bottom { get; set; }
    }
}
