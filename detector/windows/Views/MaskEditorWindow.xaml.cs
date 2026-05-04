using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using VisionGuard.ViewModels;

namespace VisionGuard.Views
{
    public partial class MaskEditorWindow : Window
    {
        private MaskEditorViewModel _vm;
        private bool _isDragging;
        private Point _dragStart;
        private Rectangle? _dragRect;
        private List<Rectangle> _maskVisuals = new List<Rectangle>();

        public List<System.Drawing.RectangleF> ResultMasks { get; private set; } = new List<System.Drawing.RectangleF>();
        public bool IsConfirmed { get; private set; }

        public MaskEditorWindow(BitmapSource? background, List<System.Drawing.RectangleF>? existingMasks)
        {
            InitializeComponent();
            _vm = new MaskEditorViewModel();
            DataContext = _vm;

            if (background != null)
            {
                BackgroundImage.Source = background;

                // 与 RegionSelectorWindow 保持一致的窗口缩放策略：
                // 按比例缩放至屏幕工作区 90%，图像 Uniform 居中
                double maxW = SystemParameters.WorkArea.Width * 0.9;
                double maxH = SystemParameters.WorkArea.Height * 0.9;
                double scale = Math.Min(1.0, Math.Min(maxW / background.Width, maxH / background.Height));

                Width = background.Width * scale;
                Height = background.Height * scale + 48; // +48 = 底部工具栏预估高度
                WindowState = WindowState.Normal;
                WindowStartupLocation = WindowStartupLocation.CenterScreen;
                ResizeMode = ResizeMode.NoResize;
            }
            else
            {
                // 无背景时回退到最大化（理论上不应发生）
                WindowState = WindowState.Maximized;
            }

            if (existingMasks != null && background != null)
            {
                double imgW = background.PixelWidth;
                double imgH = background.PixelHeight;
                foreach (var m in existingMasks)
                {
                    _vm.Masks.Add(new MaskRect
                    {
                        X = m.X * imgW,
                        Y = m.Y * imgH,
                        Width = m.Width * imgW,
                        Height = m.Height * imgH
                    });
                }
            }

            // 监听集合变化：撤销/清除后实时刷新画布
            _vm.Masks.CollectionChanged += (s, e) => RefreshMaskVisuals();

            Loaded += OnLoaded;
            SizeChanged += OnSizeChanged;
        }

        private void OnLoaded(object sender, RoutedEventArgs e)
        {
            RefreshMaskVisuals();
        }

        private void OnSizeChanged(object sender, SizeChangedEventArgs e)
        {
            RefreshMaskVisuals();
        }

        private void RefreshMaskVisuals()
        {
            MaskCanvas.Children.Clear();
            _maskVisuals.Clear();

            var imgRect = GetImageRenderRect();
            if (imgRect.Width <= 0 || imgRect.Height <= 0) return;

            foreach (var mask in _vm.Masks)
            {
                var rect = CreateMaskVisual(mask, imgRect);
                MaskCanvas.Children.Add(rect);
                _maskVisuals.Add(rect);
            }
        }

        private Rect GetImageRenderRect()
        {
            if (BackgroundImage.Source == null)
                return new Rect(0, 0, MaskCanvas.ActualWidth, MaskCanvas.ActualHeight);

            // BackgroundImage 与 MaskCanvas 是同级元素，TransformToAncestor 不可用。
            // 直接按 Stretch=Uniform 的数学规则计算图像在容器中的实际渲染矩形。
            double imgW = BackgroundImage.Source.Width;
            double imgH = BackgroundImage.Source.Height;
            double canvasW = MaskCanvas.ActualWidth;
            double canvasH = MaskCanvas.ActualHeight;

            double scale = Math.Min(canvasW / imgW, canvasH / imgH);
            double renderW = imgW * scale;
            double renderH = imgH * scale;
            double x = (canvasW - renderW) / 2.0;
            double y = (canvasH - renderH) / 2.0;

            return new Rect(x, y, renderW, renderH);
        }

        private Rectangle CreateMaskVisual(MaskRect mask, Rect imgRect)
        {
            double scaleX = imgRect.Width / BackgroundImage.Source!.Width;
            double scaleY = imgRect.Height / BackgroundImage.Source.Height;
            var rect = new Rectangle
            {
                Stroke = Brushes.LimeGreen,
                StrokeThickness = 2,
                Fill = new SolidColorBrush(Color.FromArgb(60, 255, 0, 0)),
                Width = mask.Width * scaleX,
                Height = mask.Height * scaleY,
                Cursor = Cursors.Hand
            };
            Canvas.SetLeft(rect, imgRect.X + mask.X * scaleX);
            Canvas.SetTop(rect, imgRect.Y + mask.Y * scaleY);
            rect.MouseLeftButtonDown += (s, e) =>
            {
                _vm.SelectedMask = mask;
                e.Handled = true;
            };
            return rect;
        }

        private void MaskCanvas_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            _isDragging = true;
            _dragStart = e.GetPosition(MaskCanvas);
            _dragRect = new Rectangle
            {
                Stroke = Brushes.Yellow,
                StrokeThickness = 2,
                StrokeDashArray = new DoubleCollection { 4, 2 },
                Fill = new SolidColorBrush(Color.FromArgb(40, 255, 255, 0)),
                Width = 0,
                Height = 0
            };
            Canvas.SetLeft(_dragRect, _dragStart.X);
            Canvas.SetTop(_dragRect, _dragStart.Y);
            MaskCanvas.Children.Add(_dragRect);
            MaskCanvas.CaptureMouse();
        }

        private void MaskCanvas_MouseMove(object sender, MouseEventArgs e)
        {
            if (!_isDragging || _dragRect == null) return;
            var pos = e.GetPosition(MaskCanvas);
            double x = Math.Min(_dragStart.X, pos.X);
            double y = Math.Min(_dragStart.Y, pos.Y);
            double w = Math.Abs(pos.X - _dragStart.X);
            double h = Math.Abs(pos.Y - _dragStart.Y);
            Canvas.SetLeft(_dragRect, x);
            Canvas.SetTop(_dragRect, y);
            _dragRect.Width = w;
            _dragRect.Height = h;
        }

        private void MaskCanvas_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
        {
            if (!_isDragging || _dragRect == null) return;
            _isDragging = false;
            MaskCanvas.ReleaseMouseCapture();

            var imgRect = GetImageRenderRect();
            if (_dragRect.Width < 4 || _dragRect.Height < 4 || BackgroundImage.Source == null)
            {
                MaskCanvas.Children.Remove(_dragRect);
                _dragRect = null;
                return;
            }

            // 将 Canvas 坐标转换为原始背景图像素坐标
            double scaleX = BackgroundImage.Source.Width / imgRect.Width;
            double scaleY = BackgroundImage.Source.Height / imgRect.Height;
            double x = (Canvas.GetLeft(_dragRect) - imgRect.X) * scaleX;
            double y = (Canvas.GetTop(_dragRect) - imgRect.Y) * scaleY;
            double w = _dragRect.Width * scaleX;
            double h = _dragRect.Height * scaleY;

            // Clamp 到图像边界
            x = Math.Max(0, x);
            y = Math.Max(0, y);
            w = Math.Max(1, Math.Min(BackgroundImage.Source.Width - x, w));
            h = Math.Max(1, Math.Min(BackgroundImage.Source.Height - y, h));

            var mask = new MaskRect { X = x, Y = y, Width = w, Height = h };
            _vm.Masks.Add(mask);

            MaskCanvas.Children.Remove(_dragRect);
            _dragRect = null;
            RefreshMaskVisuals();
        }

        private void Window_KeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Escape)
            {
                IsConfirmed = false;
                Close();
            }
            else if (e.Key == Key.Delete || e.Key == Key.Back)
            {
                if (_vm.SelectedMask != null)
                {
                    _vm.Masks.Remove(_vm.SelectedMask);
                    _vm.SelectedMask = null;
                    RefreshMaskVisuals();
                }
                else if (_vm.Masks.Count > 0)
                {
                    _vm.Masks.RemoveAt(_vm.Masks.Count - 1);
                    RefreshMaskVisuals();
                }
            }
        }

        private void Cancel_Click(object sender, RoutedEventArgs e)
        {
            IsConfirmed = false;
            Close();
        }

        private void Confirm_Click(object sender, RoutedEventArgs e)
        {
            if (BackgroundImage.Source == null)
            {
                IsConfirmed = false;
                Close();
                return;
            }
            var bmpSrc = BackgroundImage.Source as BitmapSource;
            ResultMasks = _vm.ToRelativeMasks((int)(bmpSrc?.PixelWidth ?? 1), (int)(bmpSrc?.PixelHeight ?? 1));
            IsConfirmed = true;
            Close();
        }
    }
}
