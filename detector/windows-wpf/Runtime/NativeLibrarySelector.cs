using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using VisionGuard.Utils;

namespace VisionGuard.Runtime
{
    /// <summary>
    /// 按运行环境选择 ONNX Runtime 原生库档位。
    ///
    /// 为什么必须这样做：
    ///  - Windows 7 只能加载原生 1.1.0：1.15/1.19 的原生库都导入 `api-ms-win-core-path-l1-1-0`
    ///    等 Windows 10 专属 API，在 Win7 上加载即失败。
    ///  - 托管层与原生层必须同代配对：托管 1.15/1.19 配原生 1.1.0 会在类型初始化时抛
    ///    `TypeInitializationException`（实测）。因此 legacy 档用托管 1.2.0，modern 档用托管 1.19.0。
    ///  - 不能用 PATH 选择原生库：Windows 的 DLL 搜索顺序中系统目录优先于 PATH，若机器上存在
    ///    系统级 onnxruntime.dll（例如随其它软件安装到 SYSTEM32），PATH 会被它抢占（实测）。
    ///    必须用绝对路径预加载，之后 Windows 按模块名复用已映射的模块。
    ///
    /// 因此：应用根目录**不得**出现 `onnxruntime.dll` / `DirectML.dll`，它们分别位于
    /// `native\legacy\` 与 `native\modern\`，由本类在启动时按档位预加载。
    /// </summary>
    internal static class NativeLibrarySelector
    {
        /// <summary>是否使用 legacy 档（Windows 7：CPU + 原生 1.1.0）。</summary>
        public static bool IsLegacy { get; private set; }

        /// <summary>本档位是否支持 DirectML（与编译期开关 `ORT_DIRECTML` 一致）。</summary>
        public static bool SupportsDirectMl { get; private set; }

        /// <summary>所选原生库目录的绝对路径。</summary>
        public static string SelectedDirectory { get; private set; } = string.Empty;

        /// <summary>档位是否已成功预加载原生库。</summary>
        public static bool IsReady { get; private set; }

        /// <summary>失败原因（仅在选择或加载失败时有值）。</summary>
        public static string FailureReason { get; private set; } = string.Empty;

        public static void Initialize()
        {
            var os = Environment.OSVersion.Version;
            bool legacyByOs = os.Major == 6 && os.Minor == 1;

            // 覆盖开关：允许在任一系统上强制验证另一档（默认按运行环境判定）。
            string forced = Environment.GetEnvironmentVariable("VISIONGUARD_ORT_PROFILE") ?? string.Empty;
            if (string.Equals(forced, "legacy", StringComparison.OrdinalIgnoreCase)) IsLegacy = true;
            else if (string.Equals(forced, "modern", StringComparison.OrdinalIgnoreCase)) IsLegacy = false;
            else IsLegacy = legacyByOs;

#if ORT_DIRECTML
            SupportsDirectMl = !IsLegacy;
#else
            SupportsDirectMl = false;
#endif

            string appDir = AppDomain.CurrentDomain.BaseDirectory;
            SelectedDirectory = Path.Combine(appDir, "native", IsLegacy ? "legacy" : "modern");

            string onnxPath = Path.Combine(SelectedDirectory, "onnxruntime.dll");
            string rootOnnx = Path.Combine(appDir, "onnxruntime.dll");
            string rootDirectMl = Path.Combine(appDir, "DirectML.dll");

            Debug.WriteLine(string.Format(
                "[native] 档位={0} 目录={1} 存在={2}",
                IsLegacy ? "legacy(1.1.0/CPU)" : "modern(1.19/DirectML)", SelectedDirectory, File.Exists(onnxPath)));

            // 档位是决定推理能力的关键运行环境信息，必须可诊断而不是只在调试输出里。
            LogManager.StaticInfo(string.Format(
                "[native] 档位={0} 目录={1} 支持DirectML={2} OS={3}",
                IsLegacy ? "legacy" : "modern", SelectedDirectory, SupportsDirectMl, Environment.OSVersion.Version));

            // 根目录残留会让所选目录失效（DllImport 会先命中根目录），必须显式报错而不是静默使用。
            if (File.Exists(rootOnnx) || File.Exists(rootDirectMl))
            {
                FailureReason = "应用根目录存在 " +
                    (File.Exists(rootOnnx) ? "onnxruntime.dll " : string.Empty) +
                    (File.Exists(rootDirectMl) ? "DirectML.dll" : string.Empty) +
                    "，会抢占所选档位目录；请清理发行目录。";
                Debug.Fail("[native] " + FailureReason);
                return;
            }

            if (!File.Exists(onnxPath))
            {
                FailureReason = "所选档位目录下找不到 onnxruntime.dll：" + SelectedDirectory;
                Debug.WriteLine("[native] " + FailureReason);
                return;
            }

            IntPtr handle = LoadLibraryW(onnxPath);
            if (handle == IntPtr.Zero)
            {
                int error = Marshal.GetLastWin32Error();
                FailureReason = string.Format("预加载失败（Win32Error={0}）：{1}", error, onnxPath);
                Debug.WriteLine("[native] " + FailureReason);
                return;
            }

            IsReady = true;
            Debug.WriteLine("[native] 已用绝对路径预加载（句柄=0x" + handle.ToInt64().ToString("X") + "）");
        }

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern IntPtr LoadLibraryW(string lpFileName);

        /// <summary>读取进程实际加载的原生库路径，用于确认档位真的生效。</summary>
        public static string LoadedOnnxRuntimePath()
        {
            try
            {
                foreach (ProcessModule module in Process.GetCurrentProcess().Modules)
                {
                    if (string.Equals(module.ModuleName, "onnxruntime.dll", StringComparison.OrdinalIgnoreCase))
                        return module.FileName;
                }
            }
            catch
            {
                // 模块枚举失败不影响主流程。
            }
            return "(尚未加载)";
        }
    }
}
