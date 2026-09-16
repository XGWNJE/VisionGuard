namespace System.Runtime.CompilerServices
{
    /// <summary>
    /// net472 不提供 IsExternalInit，编译器需要它才能支持 record 与 init 访问器。
    /// 仅用于编译兼容，不改变任何运行时语义。
    /// </summary>
    internal static class IsExternalInit
    {
    }
}
