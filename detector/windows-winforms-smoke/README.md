# WinForms test windows

This .NET Framework 4.7.2 x64 helper opens 2–16 independent top-level windows backed by local images and writes their decimal HWND values to a UTF-8 handle file. It intentionally has no browser or modern runtime dependency, so the same window-source fixture can run on Windows 7 SP1 x64.

```powershell
dotnet build detector/windows-winforms-smoke/VisionGuard.WinFormsSmoke.csproj -c Release
detector/windows-winforms-smoke/bin/Release/net472/VisionGuard.WinFormsSmoke.exe handles.txt image-1.jpg image-2.jpg
```

Close every test window to end the helper.

`VisionGuard.WinFormsInferenceSmoke.csproj` consumes the emitted HWND file and the production YOLOv5 model, runs every source through the production WinForms coordinator, and writes a machine-readable JSON report covering per-source frames, person hits, actual FPS, capacity warning, and stop isolation.
