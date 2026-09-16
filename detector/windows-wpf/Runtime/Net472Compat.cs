using System;

namespace VisionGuard.Runtime
{
    /// <summary>
    /// net472 缺失的 .NET Core API 兼容实现，集中放置以避免散落修改。
    /// 语义与 .NET 9 对应成员一致，仅补足编译期缺失。
    /// </summary>
    internal static class Net472Compat
    {
        public static int Clamp(int value, int min, int max)
            => value < min ? min : (value > max ? max : value);

        public static double Clamp(double value, double min, double max)
            => value < min ? min : (value > max ? max : value);

        public static float Clamp(float value, float min, float max)
            => value < min ? min : (value > max ? max : value);
    }
}
