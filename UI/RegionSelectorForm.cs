using System;
using System.Drawing;
using System.Windows.Forms;

namespace VisionGuard.UI
{
    /// <summary>
    /// 全屏透明覆盖窗口，用于拖拽选择屏幕捕获区域。
    /// 关闭后通过 SelectedRegion 获取结果（Rectangle.Empty 表示取消）。
    /// </summary>
    public class RegionSelectorForm : Form
    {
        public Rectangle SelectedRegion { get; private set; } = Rectangle.Empty;

        private Point   _startPoint;
        private Rectangle _current;
        private bool    _dragging;

        public RegionSelectorForm()
        {
            FormBorderStyle = FormBorderStyle.None;
            WindowState     = FormWindowState.Maximized;
            TopMost         = true;
            BackColor       = Color.Black;
            Opacity         = 0.35;
            Cursor          = Cursors.Cross;
            ShowInTaskbar   = false;

            // 按 Escape 取消
            KeyPreview = true;
            KeyDown   += (s, e) => { if (e.KeyCode == Keys.Escape) Close(); };
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            if (e.Button == MouseButtons.Left)
            {
                _startPoint = e.Location;
                _current    = new Rectangle(e.Location, Size.Empty);
                _dragging   = true;
            }
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            if (!_dragging) return;
            _current = NormalizeRect(_startPoint, e.Location);
            Invalidate();
        }

        protected override void OnMouseUp(MouseEventArgs e)
        {
            if (!_dragging || e.Button != MouseButtons.Left) return;
            _dragging = false;

            Rectangle r = NormalizeRect(_startPoint, e.Location);
            if (r.Width > 10 && r.Height > 10)
            {
                SelectedRegion = r;
                DialogResult   = DialogResult.OK;
            }
            Close();
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            if (!_dragging || _current.Width <= 0 || _current.Height <= 0) return;

            // 在半透明遮罩上挖出选区（高亮显示）
            using (var brush = new SolidBrush(Color.FromArgb(100, Color.White)))
                e.Graphics.FillRectangle(brush, _current);

            using (var pen = new Pen(Color.LimeGreen, 2))
                e.Graphics.DrawRectangle(pen, _current);

            // 显示尺寸提示
            string hint = $"{_current.Width} x {_current.Height}";
            using (var font = new Font("Consolas", 10))
            using (var brush = new SolidBrush(Color.LimeGreen))
                e.Graphics.DrawString(hint, font, brush,
                    _current.Right + 4, _current.Bottom + 4);
        }

        private static Rectangle NormalizeRect(Point a, Point b)
        {
            return new Rectangle(
                Math.Min(a.X, b.X),
                Math.Min(a.Y, b.Y),
                Math.Abs(b.X - a.X),
                Math.Abs(b.Y - a.Y));
        }
    }
}
