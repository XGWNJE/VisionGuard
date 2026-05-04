using System;
using System.Collections.ObjectModel;
using System.Linq;
using System.Windows;

namespace VisionGuard.ViewModels
{
    /// <summary>遮罩矩形（像素坐标），用于编辑器内部交互</summary>
    public class MaskRect : ViewModelBase
    {
        private double _x;
        public double X { get => _x; set => SetProperty(ref _x, value); }

        private double _y;
        public double Y { get => _y; set => SetProperty(ref _y, value); }

        private double _width;
        public double Width { get => _width; set => SetProperty(ref _width, value); }

        private double _height;
        public double Height { get => _height; set => SetProperty(ref _height, value); }

        public bool IsEmpty => Width <= 0 || Height <= 0;
    }

    public class MaskEditorViewModel : ViewModelBase
    {
        public ObservableCollection<MaskRect> Masks { get; } = new ObservableCollection<MaskRect>();

        private MaskRect? _selectedMask;
        public MaskRect? SelectedMask
        {
            get => _selectedMask;
            set => SetProperty(ref _selectedMask, value);
        }

        public RelayCommand DeleteSelectedCommand { get; }
        public RelayCommand ClearCommand { get; }
        public RelayCommand UndoCommand { get; }

        public MaskEditorViewModel()
        {
            DeleteSelectedCommand = new RelayCommand(() =>
            {
                if (SelectedMask != null)
                {
                    Masks.Remove(SelectedMask);
                    SelectedMask = null;
                }
            }, () => SelectedMask != null);

            ClearCommand = new RelayCommand(() =>
            {
                Masks.Clear();
                SelectedMask = null;
            }, () => Masks.Count > 0);

            UndoCommand = new RelayCommand(() =>
            {
                if (Masks.Count > 0)
                {
                    Masks.RemoveAt(Masks.Count - 1);
                    SelectedMask = null;
                }
            }, () => Masks.Count > 0);

            // 集合变更后刷新 Clear/Undo 按钮状态
            Masks.CollectionChanged += (s, e) =>
            {
                ClearCommand.RaiseCanExecuteChanged();
                UndoCommand.RaiseCanExecuteChanged();
            };

            // SelectedMask 变更后刷新 Delete 按钮状态
            PropertyChanged += (s, e) =>
            {
                if (e.PropertyName == nameof(SelectedMask))
                    DeleteSelectedCommand.RaiseCanExecuteChanged();
            };
        }

        /// <summary>将像素坐标转换为相对坐标 [0,1]</summary>
        public System.Collections.Generic.List<System.Drawing.RectangleF> ToRelativeMasks(double imgWidth, double imgHeight)
        {
            var list = new System.Collections.Generic.List<System.Drawing.RectangleF>();
            if (imgWidth <= 0 || imgHeight <= 0) return list;
            foreach (var m in Masks)
            {
                if (m.IsEmpty) continue;
                float x = (float)(m.X / imgWidth);
                float y = (float)(m.Y / imgHeight);
                float w = (float)(m.Width / imgWidth);
                float h = (float)(m.Height / imgHeight);
                // Clamp
                x = Math.Max(0, Math.Min(1, x));
                y = Math.Max(0, Math.Min(1, y));
                w = Math.Max(0.02f, Math.Min(1 - x, w));
                h = Math.Max(0.02f, Math.Min(1 - y, h));
                list.Add(new System.Drawing.RectangleF(x, y, w, h));
            }
            return list;
        }
    }
}
