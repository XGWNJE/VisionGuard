using System;
using System.Collections.ObjectModel;
using System.Collections.Specialized;
using System.Windows;
using System.Windows.Controls;
using VisionGuard.Detector.Windows.ViewModels;

namespace VisionGuard.Detector.Windows.Views
{
    public partial class SourceStrip : UserControl
    {
        private ObservableCollection<SourceViewModel>? _sources;
        public static readonly DependencyProperty ItemWidthProperty = DependencyProperty.Register(nameof(ItemWidth), typeof(double), typeof(SourceStrip), new PropertyMetadata(176d));
        public double ItemWidth { get => (double)GetValue(ItemWidthProperty); private set => SetValue(ItemWidthProperty, value); }
        public SourceStrip()
        {
            InitializeComponent(); SizeChanged += (_, __) => RefreshWidth();
            DataContextChanged += (_, __) => Attach(); Loaded += (_, __) => Attach();
            Unloaded += (_, __) => { if (_sources != null) _sources.CollectionChanged -= SourcesChanged; _sources = null; };
        }
        private void Attach()
        {
            if (_sources != null) _sources.CollectionChanged -= SourcesChanged;
            _sources = (DataContext as MultiSourceViewModel)?.Sources;
            if (_sources != null) _sources.CollectionChanged += SourcesChanged;
            RefreshWidth();
        }
        private void SourcesChanged(object? sender, NotifyCollectionChangedEventArgs e) => RefreshWidth();
        private void RefreshWidth() => ItemWidth = Math.Max(152, Math.Min(208, ActualWidth / Math.Max(1, Math.Min(4, _sources?.Count ?? 4)) - 8));
    }
}
