using System;
using System.Windows.Input;

namespace VisionGuard.ViewModels
{
    public class RelayCommand : ICommand
    {
        private readonly Action<object?> _execute;
        private readonly Predicate<object?>? _canExecute;
        private EventHandler? _canExecuteChanged;

        public RelayCommand(Action<object?> execute, Predicate<object?>? canExecute = null)
        {
            _execute = execute ?? throw new ArgumentNullException(nameof(execute));
            _canExecute = canExecute;
        }

        public RelayCommand(Action execute, Func<bool>? canExecute = null)
        {
            _execute = _ => execute();
            _canExecute = canExecute == null ? null : _ => canExecute();
        }

        public bool CanExecute(object? parameter) => _canExecute?.Invoke(parameter) ?? true;

        public void Execute(object? parameter) => _execute(parameter);

        public event EventHandler? CanExecuteChanged
        {
            add { _canExecuteChanged += value; }
            remove { _canExecuteChanged -= value; }
        }

        public void RaiseCanExecuteChanged()
        {
            _canExecuteChanged?.Invoke(this, EventArgs.Empty);
        }
    }
}
