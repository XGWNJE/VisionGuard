using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace VisionGuard.UI
{
    /// <summary>
    /// Win11 Fluent 风格圆角卡片面板，替代原生 GroupBox。
    /// </summary>
    public class CardPanel : Panel
    {
        private bool _hovered;

        public string Title { get; set; }

        public CardPanel()
        {
            SetStyle(
                ControlStyles.UserPaint |
                ControlStyles.AllPaintingInWmPaint |
                ControlStyles.OptimizedDoubleBuffer |
                ControlStyles.ResizeRedraw, true);

            BackColor = Color.FromArgb(42, 42, 42);
            ForeColor = Color.White;
            Padding   = new Padding(8, 22, 8, 8);
        }

        protected override void OnMouseEnter(System.EventArgs e)
        {
            _hovered = true;
            Invalidate();
            base.OnMouseEnter(e);
        }

        protected override void OnMouseLeave(System.EventArgs e)
        {
            _hovered = false;
            Invalidate();
            base.OnMouseLeave(e);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;

            Rectangle rc = new Rectangle(0, 0, Width - 1, Height - 1);

            using (GraphicsPath path = RoundRect(rc, 6))
            {
                // 填充卡片背景
                using (SolidBrush fill = new SolidBrush(Color.FromArgb(42, 42, 42)))
                    g.FillPath(fill, path);

                // 边框
                Color borderColor = _hovered
                    ? Color.FromArgb(74, 74, 74)
                    : Color.FromArgb(56, 56, 56);
                using (Pen pen = new Pen(borderColor, 1f))
                    g.DrawPath(pen, path);
            }

            // 标题文字
            if (!string.IsNullOrEmpty(Title))
            {
                using (Font f = new Font(Font.FontFamily, 8.5f, FontStyle.Bold))
                using (SolidBrush brush = new SolidBrush(Color.FromArgb(204, 204, 204)))
                    g.DrawString(Title, f, brush, new RectangleF(10, 5, Width - 20, 16));
            }
        }

        internal static GraphicsPath RoundRect(Rectangle r, int radius)
        {
            var path = new GraphicsPath();
            int d = radius * 2;
            path.AddArc(r.X,              r.Y,               d, d, 180, 90);
            path.AddArc(r.Right - d,      r.Y,               d, d, 270, 90);
            path.AddArc(r.Right - d,      r.Bottom - d,      d, d,   0, 90);
            path.AddArc(r.X,              r.Bottom - d,      d, d,  90, 90);
            path.CloseFigure();
            return path;
        }
    }
}
