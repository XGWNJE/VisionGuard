using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using Microsoft.Win32;

namespace VisionGuard.Detector.Windows.Launcher
{
    internal static class ThemedDialog
    {
        internal static bool Download(Action<CancellationToken, Action<long>> action, long size)
        {
            var palette = Palette.Read(); Exception failure = null; bool complete = false, cancelled = false;
            using (var cancel = new CancellationTokenSource())
            using (var applicationIcon = Icon.ExtractAssociatedIcon(Application.ExecutablePath))
            using (var dialog = new Form { Icon = applicationIcon, Text = "下载视觉推理节点更新", StartPosition = FormStartPosition.CenterScreen, ClientSize = new Size(480, 160), BackColor = palette.Page, ForeColor = palette.Text, Font = new Font("Microsoft YaHei UI", 10.5f), FormBorderStyle = FormBorderStyle.FixedDialog, MaximizeBox = false, MinimizeBox = false }) {
                var status = new Label { Text = "正在下载并校验…", AutoSize = true, Location = new Point(24, 24) };
                var bar = new ProgressBar { Location = new Point(24, 58), Size = new Size(432, 12) };
                var button = new ThemeButton(palette, false) { Text = "取消下载", Location = new Point(320, 96), Size = new Size(136, 40) };
                dialog.Controls.AddRange(new Control[] { status, bar, button });
                button.Click += (s, e) => { cancel.Cancel(); button.Enabled = false; status.Text = "正在取消…"; };
                dialog.FormClosing += (s, e) => { if (!complete) { e.Cancel = true; cancel.Cancel(); } };
                dialog.Shown += async (s, e) => {
                    try { await Task.Run(() => action(cancel.Token, bytes => { if (!cancel.IsCancellationRequested && !dialog.IsDisposed) dialog.BeginInvoke(new Action(() => { bar.Value = (int)Math.Min(100, bytes * 100 / size); status.Text = "已下载 " + (bytes / 1048576d).ToString("F1") + " / " + (size / 1048576d).ToString("F1") + " MiB"; })); })); }
                    catch (OperationCanceledException) { cancelled = true; }
                    catch (Exception ex) { if (cancel.IsCancellationRequested) cancelled = true; else failure = ex; }
                    complete = true; dialog.Close();
                };
                dialog.ShowDialog();
            }
            if (failure != null) throw failure;
            return !cancelled;
        }
        internal static DialogResult Show(string message, string title, MessageBoxButtons buttons, MessageBoxIcon icon)
        {
            Application.EnableVisualStyles();
            var palette = Palette.Read();
            using (var applicationIcon = Icon.ExtractAssociatedIcon(Application.ExecutablePath))
            using (var dialog = new Form())
            {
                dialog.Icon = applicationIcon;
                dialog.Text = title;
                dialog.StartPosition = FormStartPosition.CenterScreen;
                dialog.FormBorderStyle = FormBorderStyle.FixedDialog;
                dialog.MaximizeBox = false;
                dialog.MinimizeBox = false;
                dialog.ShowInTaskbar = true;
                dialog.AutoScaleMode = AutoScaleMode.Dpi;
                dialog.Font = new Font("Microsoft YaHei UI", 10.5f);
                dialog.BackColor = palette.Page;
                dialog.ForeColor = palette.Text;
                dialog.ClientSize = new Size(520, 220);
                dialog.Padding = new Padding(24);

                var layout = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 3, BackColor = palette.Page, AutoSize = false };
                layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
                layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
                layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 64));
                var heading = new Label
                {
                    Text = title, AutoSize = true, MaximumSize = new Size(472, 0),
                    Font = new Font(dialog.Font.FontFamily, 15, FontStyle.Bold),
                    ForeColor = icon == MessageBoxIcon.Error ? palette.Error : icon == MessageBoxIcon.Warning ? palette.Warning : palette.Text,
                    Margin = new Padding(0, 0, 0, 16), AccessibleName = title
                };
                layout.Controls.Add(heading, 0, 0);
                var body = new TextBox
                {
                    Text = message, Multiline = true, ReadOnly = true, WordWrap = true,
                    Font = new Font(dialog.Font.FontFamily, 12f),
                    ScrollBars = ScrollBars.Vertical, BorderStyle = BorderStyle.None,
                    BackColor = palette.Page, ForeColor = palette.Text, Dock = DockStyle.Fill,
                    Margin = Padding.Empty, TabStop = true, AccessibleName = "提示内容"
                };
                layout.Controls.Add(body, 0, 1);
                var actions = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.RightToLeft, WrapContents = false, Padding = new Padding(0, 24, 0, 0), Margin = Padding.Empty };
                layout.Controls.Add(actions, 0, 2);
                dialog.Controls.Add(layout);

                var measured = TextRenderer.MeasureText(message, body.Font, new Size(456, int.MaxValue), TextFormatFlags.WordBreak | TextFormatFlags.TextBoxControl);
                dialog.ClientSize = new Size(520, Math.Min(Math.Max(220, measured.Height + 160), Math.Max(240, Screen.PrimaryScreen.WorkingArea.Height - 80)));
                var primary = new ThemeButton(palette, true)
                {
                    Text = buttons == MessageBoxButtons.YesNo ? "现在更新" : "知道了",
                    DialogResult = buttons == MessageBoxButtons.YesNo ? DialogResult.Yes : DialogResult.OK,
                    Size = new Size(112, 40), Margin = new Padding(8, 0, 0, 0)
                };
                actions.Controls.Add(primary);
                dialog.AcceptButton = primary;
                Button initialFocus = primary;
                if (buttons == MessageBoxButtons.YesNo)
                {
                    var cancel = new ThemeButton(palette, false) { Text = "稍后", DialogResult = DialogResult.No, Size = new Size(88, 40), Margin = new Padding(8, 0, 0, 0) };
                    actions.Controls.Add(cancel);
                    dialog.CancelButton = cancel;
                    dialog.AcceptButton = cancel;
                    initialFocus = cancel;
                }
                else dialog.CancelButton = primary;
                dialog.Shown += (s, e) => initialFocus.Focus();
                dialog.HandleCreated += (s, e) => ApplyTitleBar(dialog.Handle, palette.Dark);
                UserPreferenceChangedEventHandler preferenceChanged = (s, e) =>
                {
                    if (dialog.IsDisposed || !dialog.IsHandleCreated) return;
                    try
                    {
                        dialog.BeginInvoke(new Action(() =>
                        {
                            if (dialog.IsDisposed) return;
                            palette.CopyFrom(Palette.Read());
                            dialog.BackColor = layout.BackColor = body.BackColor = palette.Page;
                            dialog.ForeColor = body.ForeColor = palette.Text;
                            heading.ForeColor = icon == MessageBoxIcon.Error ? palette.Error : icon == MessageBoxIcon.Warning ? palette.Warning : palette.Text;
                            ApplyTitleBar(dialog.Handle, palette.Dark);
                            dialog.Invalidate(true);
                        }));
                    }
                    catch (InvalidOperationException) { }
                };
                SystemEvents.UserPreferenceChanged += preferenceChanged;
                dialog.Disposed += (s, e) => SystemEvents.UserPreferenceChanged -= preferenceChanged;
                var result = dialog.ShowDialog();
                return result == DialogResult.Cancel && buttons == MessageBoxButtons.YesNo ? DialogResult.No : result;
            }
        }

        private static void ApplyTitleBar(IntPtr handle, bool darkTheme)
        {
            try
            {
                int dark = darkTheme && !SystemInformation.HighContrast ? 1 : 0;
                if (DwmSetWindowAttribute(handle, 20, ref dark, sizeof(int)) != 0)
                    DwmSetWindowAttribute(handle, 19, ref dark, sizeof(int));
            }
            catch (DllNotFoundException) { }
            catch (EntryPointNotFoundException) { }
        }

        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

        private sealed class Palette
        {
            internal bool Dark;
            internal Color Page, Surface, Text, Secondary, Border, Primary, Hover, Pressed, OnPrimary, Selected, Error, Warning;
            internal static Palette Read()
            {
                bool dark = false;
                try { using (var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize")) dark = key != null && Equals(key.GetValue("AppsUseLightTheme"), 0); }
                catch { }
                try { var mode = File.ReadAllText(System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VisionGuard", "appearance.txt")).Trim(); if (mode == "light") dark = false; else if (mode == "dark") dark = true; }
                catch { }
                var palette = dark
                    ? new Palette { Dark = true, Page = Hex("#121212"), Surface = Hex("#282828"), Text = Hex("#F2F2F2"), Secondary = Hex("#B3B3B3"), Border = Hex("#3D3D3D"), Primary = Hex("#4FBCC1"), Hover = Hex("#67CBD0"), Pressed = Hex("#40A6AC"), OnPrimary = Hex("#062E30"), Selected = Hex("#303030"), Error = Hex("#FF9696"), Warning = Hex("#F4CC79") }
                    : new Palette { Page = Hex("#F5F5F5"), Surface = Hex("#EEEEEE"), Text = Hex("#202020"), Secondary = Hex("#626262"), Border = Hex("#DDDDDD"), Primary = Hex("#087F83"), Hover = Hex("#076D71"), Pressed = Hex("#065A5E"), OnPrimary = Color.White, Selected = Hex("#EDEDED"), Error = Hex("#B83434"), Warning = Hex("#805500") };
                if (SystemInformation.HighContrast)
                {
                    palette.Page = SystemColors.Window; palette.Surface = SystemColors.Control; palette.Text = SystemColors.WindowText;
                    palette.Secondary = SystemColors.GrayText; palette.Border = SystemColors.WindowText;
                    palette.Primary = palette.Hover = palette.Pressed = SystemColors.Highlight;
                    palette.OnPrimary = SystemColors.HighlightText; palette.Selected = SystemColors.Control;
                    palette.Error = palette.Warning = SystemColors.WindowText;
                }
                return palette;
            }
            private static Color Hex(string value) => ColorTranslator.FromHtml(value);
            internal void CopyFrom(Palette value)
            {
                Dark = value.Dark; Page = value.Page; Surface = value.Surface; Text = value.Text;
                Secondary = value.Secondary; Border = value.Border; Primary = value.Primary;
                Hover = value.Hover; Pressed = value.Pressed; OnPrimary = value.OnPrimary;
                Selected = value.Selected; Error = value.Error; Warning = value.Warning;
            }
        }

        private sealed class ThemeButton : Button
        {
            private readonly Palette _palette;
            private readonly bool _primary;
            private bool _hovered, _pressed;
            internal ThemeButton(Palette palette, bool primary)
            {
                _palette = palette; _primary = primary;
                SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, true);
                Cursor = Cursors.Hand; FlatStyle = FlatStyle.Flat;
            }
            protected override void OnMouseEnter(EventArgs e) { _hovered = true; Invalidate(); base.OnMouseEnter(e); }
            protected override void OnMouseLeave(EventArgs e) { _hovered = false; Invalidate(); base.OnMouseLeave(e); }
            protected override void OnMouseDown(MouseEventArgs e) { _pressed = e.Button == MouseButtons.Left; Invalidate(); base.OnMouseDown(e); }
            protected override void OnMouseUp(MouseEventArgs e) { _pressed = false; Invalidate(); base.OnMouseUp(e); }
            protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
            protected override void OnLostFocus(EventArgs e) { _pressed = false; Invalidate(); base.OnLostFocus(e); }
            protected override void OnKeyDown(KeyEventArgs e) { if (e.KeyCode == Keys.Space) { _pressed = true; Invalidate(); } base.OnKeyDown(e); }
            protected override void OnKeyUp(KeyEventArgs e) { _pressed = false; Invalidate(); base.OnKeyUp(e); }
            protected override void OnEnabledChanged(EventArgs e) { Invalidate(); base.OnEnabledChanged(e); }
            protected override void OnPaintBackground(PaintEventArgs e) => e.Graphics.Clear(_palette.Page);
            protected override void OnPaint(PaintEventArgs e)
            {
                e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
                var background = !Enabled ? _palette.Surface : _primary ? (_pressed ? _palette.Pressed : _hovered ? _palette.Hover : _palette.Primary) : (_hovered || _pressed ? _palette.Selected : _palette.Surface);
                var foreground = !Enabled ? _palette.Secondary : _primary ? _palette.OnPrimary : _palette.Text;
                var bounds = new Rectangle(1, 1, Width - 3, Height - 3);
                int radius = Math.Max(8, 8 * DeviceDpi / 96);
                using (var shape = new GraphicsPath())
                {
                    int diameter = radius * 2;
                    shape.AddArc(bounds.Left, bounds.Top, diameter, diameter, 180, 90);
                    shape.AddArc(bounds.Right - diameter, bounds.Top, diameter, diameter, 270, 90);
                    shape.AddArc(bounds.Right - diameter, bounds.Bottom - diameter, diameter, diameter, 0, 90);
                    shape.AddArc(bounds.Left, bounds.Bottom - diameter, diameter, diameter, 90, 90); shape.CloseFigure();
                    using (var fill = new SolidBrush(background)) e.Graphics.FillPath(fill, shape);
                    using (var border = new Pen(Focused ? _palette.Text : _hovered ? _palette.Primary : _primary ? background : _palette.Border, Focused ? 2 : 1)) e.Graphics.DrawPath(border, shape);
                }
                TextRenderer.DrawText(e.Graphics, Text, Font, ClientRectangle, foreground, TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine | TextFormatFlags.NoPrefix);
            }
        }
    }
}
