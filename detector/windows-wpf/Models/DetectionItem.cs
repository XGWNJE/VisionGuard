// ┌─────────────────────────────────────────────────────────┐
// │ DetectionItem.cs                                        │
// │ 角色：检测框 UI 绑定模型（Canvas 坐标 + 标签）          │
// │ 用途：MainWindow 预览区 ItemsControl 数据模板绑定       │
// └─────────────────────────────────────────────────────────┘
namespace VisionGuard.Models
{
    /// <summary>
    /// 单个检测框的 UI 绑定表示，坐标为原始帧像素坐标。
    /// Viewbox 会自动按比例缩放到预览容器。
    /// </summary>
    public class DetectionItem
    {
        public double Left { get; set; }
        public double Top { get; set; }
        public double Width { get; set; }
        public double Height { get; set; }
        public string Label { get; set; } = string.Empty;
    }
}
