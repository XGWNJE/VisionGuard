using System.Drawing;

namespace VisionGuard.Models
{
    public class Detection
    {
        public int ClassId { get; set; }
        public string Label { get; set; }
        public float Confidence { get; set; }
        // 相对于原始捕获区域的像素坐标
        public RectangleF BoundingBox { get; set; }
    }
}
