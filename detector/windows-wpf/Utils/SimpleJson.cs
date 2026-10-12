// ┌─────────────────────────────────────────────────────────┐
// │ SimpleJson.cs                                           │
// │ 角色：轻量 JSON 序列化帮助类（无外部依赖）               │
// │ 依赖：System.Text.Json (.NET 内置)                      │
// │ 对外 API：SimpleJson.ToJson(), SimpleJson.ParseDict()   │
// └─────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace VisionGuard.Detector.Windows.Utils
{
    /// <summary>
    /// 轻量 JSON 序列化/反序列化，基于 System.Text.Json。
    /// 保持与旧版 JavaScriptSerializer 完全一致的对外 API。
    /// </summary>
    internal static class SimpleJson
    {
        private static readonly JsonSerializerOptions _opts = new JsonSerializerOptions
        {
            PropertyNameCaseInsensitive = true,
            WriteIndented = false,
        };

        /// <summary>将对象序列化为 JSON 字符串</summary>
        public static string ToJson(object obj)
        {
            try { return JsonSerializer.Serialize(obj, _opts); }
            catch { return "{}"; }
        }

        /// <summary>将 JSON 字符串反序列化为 Dictionary&lt;string, object&gt;</summary>
        public static Dictionary<string, object> ParseDict(string json)
        {
            try
            {
                var node = JsonNode.Parse(json);
                if (node is JsonObject obj)
                    return ConvertObject(obj);
            }
            catch { }
            return new Dictionary<string, object>();
        }

        /// <summary>安全获取 Dictionary 中的字符串值</summary>
        public static string GetString(Dictionary<string, object> d, string key, string fallback = "")
        {
            if (d != null && d.TryGetValue(key, out object? v) && v != null)
                return v.ToString() ?? fallback;
            return fallback;
        }

        /// <summary>安全获取整数值；缺失或类型不符时返回 fallback。</summary>
        public static int GetInt(Dictionary<string, object> d, string key, int fallback = 0)
        {
            if (d != null && d.TryGetValue(key, out object? v) && v != null)
            {
                try { return Convert.ToInt32(v); } catch { }
            }
            return fallback;
        }

        /// <summary>安全获取长整数值；缺失或类型不符时返回 fallback。</summary>
        public static long GetLong(Dictionary<string, object> d, string key, long fallback = 0)
        {
            if (d != null && d.TryGetValue(key, out object? v) && v != null)
            {
                try { return Convert.ToInt64(v); } catch { }
            }
            return fallback;
        }

        /// <summary>安全获取布尔值；缺失或类型不符时返回 fallback。</summary>
        public static bool GetBool(Dictionary<string, object> d, string key, bool fallback = false)
        {
            if (d != null && d.TryGetValue(key, out object? v) && v != null)
            {
                if (v is bool b) return b;
                try { return Convert.ToBoolean(v); } catch { }
            }
            return fallback;
        }

        /// <summary>
        /// 反序列化 JSON 到指定类型，失败返回 default(T)。
        /// 用于解析持久化的复合对象列表（如 MaskRegions）。
        /// </summary>
        public static T? Deserialize<T>(string json)
        {
            if (string.IsNullOrWhiteSpace(json)) return default;
            try { return JsonSerializer.Deserialize<T>(json, _opts); }
            catch { return default; }
        }

        // ── 内部递归转换：JsonNode → 原生 .NET 类型 ──
        private static Dictionary<string, object> ConvertObject(JsonObject obj)
        {
            var dict = new Dictionary<string, object>(StringComparer.OrdinalIgnoreCase);
            foreach (var prop in obj)
            {
                if (prop.Key != null && prop.Value != null)
                    dict[prop.Key] = ConvertNode(prop.Value);
            }
            return dict;
        }

        private static object ConvertNode(JsonNode node)
        {
            if (node is JsonObject obj) return ConvertObject(obj);
            if (node is JsonArray arr) return ConvertArray(arr);
            if (node is JsonValue val)
            {
                if (val.TryGetValue<string>(out var s)) return s;
                if (val.TryGetValue<int>(out var i)) return i;
                if (val.TryGetValue<long>(out var l)) return l;
                if (val.TryGetValue<double>(out var d)) return d;
                if (val.TryGetValue<bool>(out var b)) return b;
                return val.ToString();
            }
            return node.ToString();
        }

        private static List<object> ConvertArray(JsonArray arr)
        {
            var list = new List<object>();
            foreach (var item in arr)
            {
                if (item != null)
                    list.Add(ConvertNode(item));
            }
            return list;
        }
    }
}
