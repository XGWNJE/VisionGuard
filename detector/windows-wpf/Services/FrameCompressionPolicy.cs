using System.Drawing;
using VisionGuard.Detector.Windows.Utils;

namespace VisionGuard.Detector.Windows.Services
{
    /// <summary>固定镜头安静时降低 JPEG 体积；画面变化立即恢复原质量，帧率与时间戳保持不变。</summary>
    public sealed class FrameCompressionPolicy
    {
        private readonly int[] _previous = new int[256];
        private int _width, _height, _quietFrames;

        public long SelectQuality(Bitmap frame) => GdiGate.Run(() =>
        {
            bool changed = frame.Width != _width || frame.Height != _height;
            for (int index = 0; index < _previous.Length; index++)
            {
                int pixel = frame.GetPixel((index % 16) * (frame.Width - 1) / 15, (index / 16) * (frame.Height - 1) / 15).ToArgb();
                for (int shift = 0; shift <= 16; shift += 8)
                    changed |= System.Math.Abs(((pixel >> shift) & 255) - ((_previous[index] >> shift) & 255)) > 3;
                _previous[index] = pixel;
            }
            _width = frame.Width; _height = frame.Height;
            _quietFrames = changed ? 0 : _quietFrames + 1;
            return _quietFrames >= 2 ? 40L : MediaPublishService.JpegQuality;
        });
    }
}
