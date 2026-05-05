// MaskEditorForm.cs — 遮罩编辑器：在捕获快照上拖拽绘制多个遮罩矩形
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace VisionGuard.UI
{
    public class MaskEditorForm : Form
    {
        private const float MinRelativeSize = 0.02f;

        private readonly Bitmap _background;
        private readonly List<RectangleF> _masks;
        private bool      _dragging;
        private Point     _startPoint;
        private Rectangle _draggingRect;

        private Panel   _toolbar;
        private Panel   _canvas;
        private Button  _btnUndo;
        private Button  _btnClear;
        private Button  _btnCancel;
        private Button  _btnConfirm;
        private Label   _lblHint;

        public IReadOnlyList<RectangleF> Masks { get; private set; } = new List<RectangleF>();

        public MaskEditorForm(Bitmap background, IList<RectangleF> initialMasks)
        {
            _background = background ?? throw new ArgumentNullException(nameof(background));

            _masks = new List<RectangleF>();
            if (initialMasks != null)
            {
                int W = background.Width;
                int H = background.Height;
                foreach (var r in initialMasks)
                {
                    float x = r.X * W;
                    float y = r.Y * H;
                    float w = r.Width * W;
                    float h = r.Height * H;
                    if (w > 0 && h > 0)
                        _masks.Add(new RectangleF(x, y, w, h));
                }
            }

            BuildUI();
        }

        // ── UI ────────────────────────────────────────────────────────

        private void BuildUI()
        {
            const int toolbarH = 48;
            int bgW = _background.Width;
            int bgH = _background.Height;

            Rectangle workArea = Screen.PrimaryScreen.WorkingArea;
            int maxW = workArea.Width - 40;
            int maxH = workArea.Height - 40 - toolbarH;
            float scale = 1.0f;
            if (bgW > maxW || bgH > maxH)
                scale = Math.Min((float)maxW / bgW, (float)maxH / bgH);
            int canvasW = (int)(bgW * scale);
            int canvasH = (int)(bgH * scale);

            Text            = "遮罩区域编辑器";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox     = false;
            MinimizeBox     = false;
            StartPosition   = FormStartPosition.CenterScreen;
            ClientSize      = new Size(canvasW, canvasH + toolbarH);
            KeyPreview      = true;
            DoubleBuffered  = true;

            // Toolbar
            _toolbar = new Panel { Dock = DockStyle.Top, Height = toolbarH };
            Controls.Add(_toolbar);

            _btnUndo    = MakeToolButton("撤销");
            _btnClear   = MakeToolButton("清空");
            _btnCancel  = MakeToolButton("取消");
            _btnConfirm = MakeToolButton("确定");

            _btnUndo.Top    = (toolbarH - _btnUndo.Height)    / 2;
            _btnClear.Top   = (toolbarH - _btnClear.Height)   / 2;
            _btnCancel.Top  = (toolbarH - _btnCancel.Height)  / 2;
            _btnConfirm.Top = (toolbarH - _btnConfirm.Height) / 2;

            _btnUndo.Left    = 12;
            _btnClear.Left   = _btnUndo.Right + 8;
            _btnConfirm.Left = canvasW - _btnConfirm.Width - 12;
            _btnCancel.Left  = _btnConfirm.Left - _btnCancel.Width - 8;

            _toolbar.Controls.Add(_btnUndo);
            _toolbar.Controls.Add(_btnClear);
            _toolbar.Controls.Add(_btnCancel);
            _toolbar.Controls.Add(_btnConfirm);

            _lblHint = new Label
            {
                AutoSize = true,
                Text     = "拖拽鼠标绘制矩形遮罩；遮罩区域将在推理与报警截图中显示为黑色",
            };
            _toolbar.Controls.Add(_lblHint);
            _toolbar.Resize += (s, e) =>
            {
                _lblHint.Left = _btnClear.Right + 16;
                _lblHint.Top  = (toolbarH - _lblHint.Height) / 2;
            };
            _lblHint.Left = _btnClear.Right + 16;
            _lblHint.Top  = (toolbarH - _lblHint.Height) / 2;

            // Canvas
            _canvas = new Panel
            {
                Dock      = DockStyle.Fill,
                BackColor = Color.Black,
                Cursor    = Cursors.Cross,
            };
            EnableDoubleBuffering(_canvas);
            Controls.Add(_canvas);
            _canvas.BringToFront();

            _canvas.Paint     += Canvas_Paint;
            _canvas.MouseDown += Canvas_MouseDown;
            _canvas.MouseMove += Canvas_MouseMove;
            _canvas.MouseUp   += Canvas_MouseUp;

            // Events
            _btnUndo.Click    += (s, e) => Undo();
            _btnClear.Click   += (s, e) => Clear();
            _btnCancel.Click  += (s, e) => { DialogResult = DialogResult.Cancel; Close(); };
            _btnConfirm.Click += (s, e) => Confirm();

            KeyDown += (s, e) =>
            {
                if (e.KeyCode == Keys.Escape)
                {
                    DialogResult = DialogResult.Cancel;
                    Close();
                }
            };
        }

        private static Button MakeToolButton(string text)
        {
            return new Button { Text = text, Width = 100, Height = 32 };
        }

        // ── Mouse events ──────────────────────────────────────────────

        private void Canvas_MouseDown(object sender, MouseEventArgs e)
        {
            if (e.Button != MouseButtons.Left) return;
            _dragging     = true;
            _startPoint   = e.Location;
            _draggingRect = new Rectangle(e.Location, Size.Empty);
        }

        private void Canvas_MouseMove(object sender, MouseEventArgs e)
        {
            if (!_dragging) return;
            _draggingRect = NormalizeRect(_startPoint, e.Location);
            _canvas.Invalidate();
        }

        private void Canvas_MouseUp(object sender, MouseEventArgs e)
        {
            if (!_dragging || e.Button != MouseButtons.Left) return;
            _dragging = false;

            Rectangle rCanvas = NormalizeRect(_startPoint, e.Location);
            _draggingRect = Rectangle.Empty;
            rCanvas.Intersect(_canvas.ClientRectangle);

            if (rCanvas.Width > 0 && rCanvas.Height > 0)
            {
                RectangleF rImg = CanvasToImage(rCanvas);
                float relW = rImg.Width  / _background.Width;
                float relH = rImg.Height / _background.Height;
                if (relW >= MinRelativeSize && relH >= MinRelativeSize)
                    _masks.Add(rImg);
            }

            _canvas.Invalidate();
        }

        // ── Paint ─────────────────────────────────────────────────────

        private void Canvas_Paint(object sender, PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode     = SmoothingMode.None;
            g.InterpolationMode = InterpolationMode.HighQualityBilinear;

            g.DrawImage(_background, 0, 0, _canvas.ClientSize.Width, _canvas.ClientSize.Height);

            using (var fill = new SolidBrush(Color.FromArgb(100, Color.Red)))
            using (var pen  = new Pen(Color.Red, 2f))
            {
                foreach (var m in _masks)
                {
                    Rectangle rc = ImageToCanvas(m);
                    g.FillRectangle(fill, rc);
                    g.DrawRectangle(pen, rc);
                }
            }

            if (_dragging && _draggingRect.Width > 0 && _draggingRect.Height > 0)
            {
                using (var fill = new SolidBrush(Color.FromArgb(80, Color.Gold)))
                    g.FillRectangle(fill, _draggingRect);
                using (var pen = new Pen(Color.Gold, 2f) { DashStyle = DashStyle.Dash })
                    g.DrawRectangle(pen, _draggingRect);

                string hint = $"{_draggingRect.Width} x {_draggingRect.Height}";
                using (var font = new Font("Consolas", 10f))
                using (var brush = new SolidBrush(Color.Gold))
                    g.DrawString(hint, font, brush,
                        _draggingRect.Right + 4, _draggingRect.Bottom + 4);
            }

            string status = _masks.Count == 0 ? "未绘制遮罩" : $"已绘制 {_masks.Count} 个遮罩";
            using (var font  = new Font("Microsoft Sans Serif", 10f, FontStyle.Bold))
            using (var brush = new SolidBrush(Color.FromArgb(220, Color.White)))
            using (var bg    = new SolidBrush(Color.FromArgb(140, Color.Black)))
            {
                SizeF sz = g.MeasureString(status, font);
                var bgRect = new RectangleF(8, 8, sz.Width + 12, sz.Height + 6);
                g.FillRectangle(bg, bgRect);
                g.DrawString(status, font, brush, 14, 11);
            }
        }

        // ── Toolbar commands ──────────────────────────────────────────

        private void Undo()
        {
            if (_masks.Count == 0) return;
            _masks.RemoveAt(_masks.Count - 1);
            _canvas.Invalidate();
        }

        private void Clear()
        {
            if (_masks.Count == 0) return;
            _masks.Clear();
            _canvas.Invalidate();
        }

        private void Confirm()
        {
            var result = new List<RectangleF>(_masks.Count);
            float W = _background.Width;
            float H = _background.Height;
            foreach (var r in _masks)
            {
                float x = Math.Max(0f, Math.Min(1f, r.X / W));
                float y = Math.Max(0f, Math.Min(1f, r.Y / H));
                float w = Math.Max(0f, Math.Min(1f - x, r.Width  / W));
                float h = Math.Max(0f, Math.Min(1f - y, r.Height / H));
                if (w >= MinRelativeSize && h >= MinRelativeSize)
                    result.Add(new RectangleF(x, y, w, h));
            }
            Masks = result;
            DialogResult = DialogResult.OK;
            Close();
        }

        // ── Coordinate conversion ─────────────────────────────────────

        private Rectangle ImageToCanvas(RectangleF imgRect)
        {
            float sx = (float)_canvas.ClientSize.Width  / _background.Width;
            float sy = (float)_canvas.ClientSize.Height / _background.Height;
            return new Rectangle(
                (int)Math.Round(imgRect.X      * sx),
                (int)Math.Round(imgRect.Y      * sy),
                (int)Math.Round(imgRect.Width  * sx),
                (int)Math.Round(imgRect.Height * sy));
        }

        private RectangleF CanvasToImage(Rectangle canvasRect)
        {
            float sx = (float)_background.Width  / _canvas.ClientSize.Width;
            float sy = (float)_background.Height / _canvas.ClientSize.Height;
            return new RectangleF(
                canvasRect.X      * sx,
                canvasRect.Y      * sy,
                canvasRect.Width  * sx,
                canvasRect.Height * sy);
        }

        private static Rectangle NormalizeRect(Point a, Point b)
        {
            return new Rectangle(
                Math.Min(a.X, b.X),
                Math.Min(a.Y, b.Y),
                Math.Abs(b.X - a.X),
                Math.Abs(b.Y - a.Y));
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            base.OnFormClosed(e);
        }

        private static void EnableDoubleBuffering(Control c)
        {
            typeof(Control).GetProperty("DoubleBuffered",
                System.Reflection.BindingFlags.Instance |
                System.Reflection.BindingFlags.NonPublic)
                ?.SetValue(c, true);
        }
    }
}
