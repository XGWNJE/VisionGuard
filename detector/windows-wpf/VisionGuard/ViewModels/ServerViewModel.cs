namespace VisionGuard.ViewModels
{
    public class ServerViewModel : ViewModelBase
    {
        private string _connectionState = "● 未连接";
        public string ConnectionState
        {
            get => _connectionState;
            set => SetProperty(ref _connectionState, value);
        }

        private string _deviceName = System.Environment.MachineName;
        public string DeviceName
        {
            get => _deviceName;
            set => SetProperty(ref _deviceName, value);
        }

        public RelayCommand RetryCommand { get; }
        public RelayCommand ApplyNameCommand { get; }

        public ServerViewModel()
        {
            RetryCommand = new RelayCommand(() => { });
            ApplyNameCommand = new RelayCommand(() => { });
        }
    }
}
