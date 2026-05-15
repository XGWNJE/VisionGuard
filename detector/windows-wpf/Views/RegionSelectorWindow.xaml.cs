using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;

namespace VisionGuard.Views
{
    public partial class RegionSelectorWindow : Window
    {
        private Point _startPoint;
        private bool _isDragging;

        /// <summary>选区结果（源图像物理像素坐标）。
        /// 窗口子区域模式：相对于捕获窗口截图的物理像素坐标。
        /// 全屏模式：相对于屏幕的物理像素坐标。</summary>
        public System.Drawing.Rectangle SelectedRegion { get; private set; }
        public bool IsConfirmed { get; private set; }

        /// <summary>是否为窗口子区域模式（有背景图）。</summary>
        public bool IsWindowMode { get; private set; }

        public RegionSelectorWindow(BitmapSource? background)
        {
            InitializeComponent();

            if (background != null)
            {
                // 窗口子区域模式：限制窗口不超过屏幕工作区 90%，保持宽高比缩放。
                // 坐标在 MouseUp 中通过归一化（DIP / CanvasActualSize）重映射到源图像物理像素，
                // 不受 DPI 缩放影响。
                IsWindowMode = true;
                BackgroundImage.Source = background;

                double maxW = SystemParameters.WorkArea.Width * 0.9;
                double maxH = SystemParameters.WorkArea.Height * 0.9;
                double scale = Math.Min(1.0, Math.Min(maxW / background.Width, maxH / background.Height));

                Width = background.Width * scale;
                Height = background.Height * scale;

                WindowState = WindowState.Normal;
                WindowStartupLocation = WindowStartupLocation.CenterScreen;
                ResizeMode = ResizeMode.NoResize;
            }
            else
            {
                // 全屏区域模式：覆盖所有显示器
                IsWindowMode = false;
                WindowState = WindowState.Normal;
                ResizeMode = ResizeMode.NoResize;
                Left = SystemParameters.VirtualScreenLeft;
                Top = SystemParameters.VirtualScreenTop;
                Width = SystemParameters.VirtualScreenWidth;
                Height = SystemParameters.VirtualScreenHeight;
                BackgroundImage.Visibility = Visibility.Collapsed;
            }

            PreviewKeyDown += OnKeyDown;
            PreviewMouseLeftButtonDown += OnMouseLeftButtonDown;
            PreviewMouseMove += OnMouseMove;
            PreviewMouseLeftButtonUp += OnMouseLeftButtonUp;
        }

        private void OnKeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Escape)
            {
                IsConfirmed = false;
                Close();
            }
        }

        private void OnMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            _isDragging = true;
            _startPoint = e.GetPosition(OverlayCanvas);
            SelectionRect.Visibility = Visibility.Visible;
            Canvas.SetLeft(SelectionRect, _startPoint.X);
            Canvas.SetTop(SelectionRect, _startPoint.Y);
            SelectionRect.Width = 0;
            SelectionRect.Height = 0;
            CaptureMouse();
        }

        private void OnMouseMove(object sender, MouseEventArgs e)
        {
            if (!_isDragging) return;
            var pos = e.GetPosition(OverlayCanvas);
            double x = Math.Min(_startPoint.X, pos.X);
            double y = Math.Min(_startPoint.Y, pos.Y);
            double w = Math.Abs(pos.X - _startPoint.X);
            double h = Math.Abs(pos.Y - _startPoint.Y);
            Canvas.SetLeft(SelectionRect, x);
            Canvas.SetTop(SelectionRect, y);
            SelectionRect.Width = w;
            SelectionRect.Height = h;
            DimensionLabel.Visibility = Visibility.Visible;
            DimensionLabel.Text = $"{(int)w} × {(int)h}";
            Canvas.SetLeft(DimensionLabel, x + w + 4);
            Canvas.SetTop(DimensionLabel, y + h + 4);
        }

        private void OnMouseLeftButtonUp(object sender, MouseButtonEventArgs e)
        {
            if (!_isDragging) return;
            _isDragging = false;
            ReleaseMouseCapture();

            double left = Canvas.GetLeft(SelectionRect);
            double top = Canvas.GetTop(SelectionRect);
            double width = SelectionRect.Width;
            double height = SelectionRect.Height;

            // Clamp 到画布边界（防止鼠标在画布外释放导致负坐标）
            double canvasW = OverlayCanvas.ActualWidth;
            double canvasH = OverlayCanvas.ActualHeight;
            left   = Math.Max(0, Math.Min(left,   canvasW));
            top    = Math.Max(0, Math.Min(top,    canvasH));
            width  = Math.Max(0, Math.Min(width,  canvasW - left));
            height = Math.Max(0, Math.Min(height, canvasH - top));

            DimensionLabel.Visibility = Visibility.Collapsed;

            if (width < 4 || height < 4)
            {
                IsConfirmed = false;
                Close();
                return;
            }

            // 将 OverlayCanvas DIP 坐标归一化后映射到源图像物理像素
            double normX = left / canvasW;
            double normY = top / canvasH;
            double normW = width / canvasW;
            double normH = height / canvasH;

            if (BackgroundImage.Source is BitmapSource bmpSrc)
            {
                int pixelLeft = (int)(normX * bmpSrc.PixelWidth);
                int pixelTop = (int)(normY * bmpSrc.PixelHeight);
                int pixelWidth = (int)(normW * bmpSrc.PixelWidth);
                int pixelHeight = (int)(normH * bmpSrc.PixelHeight);
                SelectedRegion = new System.Drawing.Rectangle(pixelLeft, pixelTop, pixelWidth, pixelHeight);
            }
            else
            {
                // 全屏模式：OverlayCanvas 填满屏幕，用 DPI 缩放因子转换
                var dpi = System.Windows.Media.VisualTreeHelper.GetDpi(this);
                SelectedRegion = new System.Drawing.Rectangle(
                    (int)(left * dpi.DpiScaleX),
                    (int)(top * dpi.DpiScaleY),
                    (int)(width * dpi.DpiScaleX),
                    (int)(height * dpi.DpiScaleY));
            }

            IsConfirmed = true;
            Close();
        }
    }
}
