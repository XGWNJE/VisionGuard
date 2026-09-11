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
            GetClampedSelection(pos, out double x, out double y, out double w, out double h);
            Canvas.SetLeft(SelectionRect, x);
            Canvas.SetTop(SelectionRect, y);
            SelectionRect.Width = w;
            SelectionRect.Height = h;

            var captureRegion = MapToCapturePixels(x, y, w, h);
            DimensionLabel.Visibility = Visibility.Visible;
            DimensionLabel.Text = VisionGuard.Capture.CaptureSizeConstraints.IsValid(captureRegion)
                ? $"{captureRegion.Width} × {captureRegion.Height} px"
                : $"{captureRegion.Width} × {captureRegion.Height} px · 最小 101 × 101";
            Canvas.SetLeft(DimensionLabel, x + w + 4);
            Canvas.SetTop(DimensionLabel, y + h + 4);
        }

        private void OnMouseLeftButtonUp(object sender, MouseButtonEventArgs e)
        {
            if (!_isDragging) return;
            _isDragging = false;
            ReleaseMouseCapture();

            var releasePoint = e.GetPosition(OverlayCanvas);
            GetClampedSelection(releasePoint, out double left, out double top, out double width, out double height);

            if (width < 4 || height < 4)
            {
                IsConfirmed = false;
                Close();
                return;
            }

            SelectedRegion = MapToCapturePixels(left, top, width, height);

            if (!VisionGuard.Capture.CaptureSizeConstraints.IsValid(SelectedRegion))
            {
                DimensionLabel.Visibility = Visibility.Visible;
                DimensionLabel.Text = $"{SelectedRegion.Width} × {SelectedRegion.Height} px · 选区过小";
                HintText.Text = "选区过小 · 宽度和高度必须都至少为 101 px · 请重新拖拽";
                IsConfirmed = false;
                return;
            }

            DimensionLabel.Visibility = Visibility.Collapsed;
            IsConfirmed = true;
            Close();
        }

        private void GetClampedSelection(
            Point currentPoint,
            out double left,
            out double top,
            out double width,
            out double height)
        {
            double canvasWidth = OverlayCanvas.ActualWidth;
            double canvasHeight = OverlayCanvas.ActualHeight;
            double currentX = Math.Max(0, Math.Min(currentPoint.X, canvasWidth));
            double currentY = Math.Max(0, Math.Min(currentPoint.Y, canvasHeight));
            double startX = Math.Max(0, Math.Min(_startPoint.X, canvasWidth));
            double startY = Math.Max(0, Math.Min(_startPoint.Y, canvasHeight));

            left = Math.Min(startX, currentX);
            top = Math.Min(startY, currentY);
            width = Math.Abs(currentX - startX);
            height = Math.Abs(currentY - startY);
        }

        private System.Drawing.Rectangle MapToCapturePixels(double left, double top, double width, double height)
        {
            double scaleX;
            double scaleY;

            if (BackgroundImage.Source is BitmapSource bitmap)
            {
                scaleX = OverlayCanvas.ActualWidth > 0 ? bitmap.PixelWidth / OverlayCanvas.ActualWidth : 0;
                scaleY = OverlayCanvas.ActualHeight > 0 ? bitmap.PixelHeight / OverlayCanvas.ActualHeight : 0;
            }
            else
            {
                var dpi = System.Windows.Media.VisualTreeHelper.GetDpi(this);
                scaleX = dpi.DpiScaleX;
                scaleY = dpi.DpiScaleY;
            }

            return VisionGuard.Capture.CaptureSizeConstraints.MapToCapturePixels(
                left, top, width, height, scaleX, scaleY);
        }
    }
}
