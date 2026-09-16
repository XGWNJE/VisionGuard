using System;
using System.Collections.Generic;

namespace VisionGuard.Runtime
{
    /// <summary>
    /// net472 缺失的 .NET Core API 兼容实现，集中放置以避免散落修改。
    /// 与 detector/windows-wpf/Runtime/Net472Compat.cs 保持一致。
    /// </summary>
    internal static class Net472Compat
    {
        public static int Clamp(int value, int min, int max)
            => value < min ? min : (value > max ? max : value);

        public static double Clamp(double value, double min, double max)
            => value < min ? min : (value > max ? max : value);

        public static float Clamp(float value, float min, float max)
            => value < min ? min : (value > max ? max : value);

        public static TValue GetValueOrDefault<TKey, TValue>(IReadOnlyDictionary<TKey, TValue> dictionary, TKey key)
            => dictionary != null && dictionary.TryGetValue(key, out var value) ? value : default;

        public static TValue GetValueOrDefault<TKey, TValue>(IReadOnlyDictionary<TKey, TValue> dictionary, TKey key, TValue fallback)
            => dictionary != null && dictionary.TryGetValue(key, out var value) ? value : fallback;
    }
}
