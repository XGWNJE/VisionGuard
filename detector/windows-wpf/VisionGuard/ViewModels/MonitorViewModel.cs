namespace VisionGuard.ViewModels
{
    public class MonitorViewModel : ViewModelBase
    {
        private string _regionInfo = "未选择区域";
        public string RegionInfo
        {
            get => _regionInfo;
            set => SetProperty(ref _regionInfo, value);
        }

        private string _maskInfo = "当前遮罩：—";
        public string MaskInfo
        {
            get => _maskInfo;
            set => SetProperty(ref _maskInfo, value);
        }

        private bool _isMonitoring;
        public bool IsMonitoring
        {
            get => _isMonitoring;
            set
            {
                if (SetProperty(ref _isMonitoring, value))
                {
                    OnPropertyChanged(nameof(CanStart));
                    OnPropertyChanged(nameof(CanStop));
                }
            }
        }

        public bool CanStart => !IsMonitoring;
        public bool CanStop => IsMonitoring;

        public RelayCommand StartCommand { get; }
        public RelayCommand StopCommand { get; }
        public RelayCommand PickWindowCommand { get; }
        public RelayCommand SelectRegionCommand { get; }
        public RelayCommand EditMasksCommand { get; }

        public MonitorViewModel()
        {
            StartCommand = new RelayCommand(() => IsMonitoring = true, () => CanStart);
            StopCommand = new RelayCommand(() => IsMonitoring = false, () => CanStop);
            PickWindowCommand = new RelayCommand(() => { }, () => CanStart);
            SelectRegionCommand = new RelayCommand(() => { }, () => CanStart);
            EditMasksCommand = new RelayCommand(() => { }, () => CanStart);
        }
    }
}
