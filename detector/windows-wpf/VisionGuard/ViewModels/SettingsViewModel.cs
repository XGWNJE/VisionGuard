namespace VisionGuard.ViewModels
{
    public class SettingsViewModel : ViewModelBase
    {
        private int _threshold = 45;
        public int Threshold
        {
            get => _threshold;
            set => SetProperty(ref _threshold, value);
        }

        private int _samplingRate = 3;
        public int SamplingRate
        {
            get => _samplingRate;
            set => SetProperty(ref _samplingRate, value);
        }

        private int _cooldown = 5;
        public int Cooldown
        {
            get => _cooldown;
            set => SetProperty(ref _cooldown, value);
        }

        private int _selectedModelIndex;
        public int SelectedModelIndex
        {
            get => _selectedModelIndex;
            set => SetProperty(ref _selectedModelIndex, value);
        }

        public string ThresholdText => $"{Threshold}%";
        public string SamplingRateText => $"{SamplingRate} 次/秒";
        public string CooldownText => $"{Cooldown} 秒";

        public SettingsViewModel()
        {
            PropertyChanged += (s, e) =>
            {
                if (e.PropertyName == nameof(Threshold)) OnPropertyChanged(nameof(ThresholdText));
                if (e.PropertyName == nameof(SamplingRate)) OnPropertyChanged(nameof(SamplingRateText));
                if (e.PropertyName == nameof(Cooldown)) OnPropertyChanged(nameof(CooldownText));
            };
        }
    }
}
