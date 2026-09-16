using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Threading.Tasks;

namespace VisionGuard.Runtime
{
    /// <summary>
    /// net472 缺失的 .NET Core API 兼容实现。
    /// 与 detector/windows-wpf/Runtime/Net472Compat.cs 保持同一语义；夹具工具单独保留一份，
    /// 因为它不引用被测端工程的可执行入口，不能继承那里的兼容层。
    /// </summary>
    internal static class Net472Compat
    {
        public static int Clamp(int value, int min, int max)
            => value < min ? min : (value > max ? max : value);

        /// <summary>取代 .NET Core 的 IReadOnlyDictionary.GetValueOrDefault。</summary>
        public static TValue DictOrDefault<TKey, TValue>(IReadOnlyDictionary<TKey, TValue> dictionary, TKey key)
            => dictionary != null && dictionary.TryGetValue(key, out var value) ? value : default;

        /// <summary>取代 .NET Core 的 IReadOnlyDictionary.GetValueOrDefault（带默认值）。</summary>
        public static TValue DictOrDefault<TKey, TValue>(IReadOnlyDictionary<TKey, TValue> dictionary, TKey key, TValue fallback)
            => dictionary != null && dictionary.TryGetValue(key, out var value) ? value : fallback;

        /// <summary>取代 .NET Core 的 string.Contains(string, StringComparison)。</summary>
        public static bool Contains(string text, string value, StringComparison comparison)
            => text != null && text.IndexOf(value, comparison) >= 0;

        /// <summary>取代 .NET Core 的 File.WriteAllTextAsync。</summary>
        public static Task WriteAllTextAsync(string path, string contents)
            => Task.Run(() => File.WriteAllText(path, contents, new UTF8Encoding(false)));
    }
}
