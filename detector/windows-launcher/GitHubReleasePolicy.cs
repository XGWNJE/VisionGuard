using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;

namespace VisionGuard.Detector.Windows.Launcher
{
    internal sealed class GitHubAsset
    {
        public string Version, Url, Sha256;
        public long Size;
    }
    internal static class GitHubReleasePolicy
    {
        internal const string Repository = "XGWNJE/VisionGuard";
        private static string Text(Dictionary<string, object> row, string key) { object value; return row.TryGetValue(key, out value) ? value as string ?? "" : ""; }
        private static bool Flag(Dictionary<string, object> row, string key) { object value; return row.TryGetValue(key, out value) && value is bool && (bool)value; }
        private static string TagVersion(Dictionary<string, object> row) { string tag = Text(row, "tag_name"); return tag.StartsWith("v", StringComparison.Ordinal) ? tag.Substring(1) : tag; }
        internal static Version StableVersion(string text)
        {
            Version version;
            return Regex.IsMatch(text ?? "", @"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$") && Version.TryParse(text, out version) ? version : null;
        }
        private static DateTime Published(Dictionary<string, object> row)
        {
            DateTime time;
            return DateTime.TryParse(Text(row, "published_at"), CultureInfo.InvariantCulture, DateTimeStyles.AdjustToUniversal | DateTimeStyles.AssumeUniversal, out time) ? time : DateTime.MinValue;
        }
        internal static GitHubAsset Select(IEnumerable<Dictionary<string, object>> releases, string current, string prefix = "VisionGuard-WPF-v", string suffix = ".zip")
        {
            var installed = StableVersion(current) ?? throw new InvalidDataException("当前客户端版本无效。");
            var stable = releases.Where(row => !Flag(row, "draft") && !Flag(row, "prerelease") && StableVersion(TagVersion(row)) != null && Published(row) != DateTime.MinValue).OrderByDescending(Published).ToList();
            var baseline = stable.Where(row => TagVersion(row) == current).Select(Published).DefaultIfEmpty(DateTime.MinValue).Max();
            foreach (var release in stable)
            {
                string version = TagVersion(release);
                var parsed = StableVersion(version);
                if (parsed <= installed || baseline != DateTime.MinValue && Published(release) <= baseline) continue;
                // An unpublished development build has no date anchor. Stay in its version line.
                if (baseline == DateTime.MinValue && parsed.Major != installed.Major) continue;
                object value;
                var assets = release.TryGetValue("assets", out value) ? value as IEnumerable : null;
                if (assets == null) continue;
                string name = prefix + version + suffix;
                var matches = assets.Cast<object>().OfType<Dictionary<string, object>>().Where(item => Text(item, "name") == name).ToList();
                if (matches.Count == 0) continue; // A release may update only another client.
                if (matches.Count != 1) throw new InvalidDataException("发行文件重复。");
                var asset = matches[0]; string digest = Text(asset, "digest"), url = Text(asset, "browser_download_url");
                long size; Uri uri;
                if (Text(asset, "state") != "uploaded" || !asset.TryGetValue("size", out value) || !long.TryParse(Convert.ToString(value, CultureInfo.InvariantCulture), out size) || size <= 0 || size > 512L * 1024 * 1024
                    || !Regex.IsMatch(digest, @"^sha256:[0-9a-fA-F]{64}$") || !Uri.TryCreate(url, UriKind.Absolute, out uri) || uri.Scheme != "https" || !string.IsNullOrEmpty(uri.UserInfo) || !uri.IsDefaultPort || uri.Host != "github.com"
                    || Uri.UnescapeDataString(uri.AbsolutePath) != "/" + Repository + "/releases/download/" + Text(release, "tag_name") + "/" + name)
                    throw new InvalidDataException("GitHub 发行文件元数据不完整或不可信。");
                return new GitHubAsset { Version = version, Url = url, Sha256 = digest.Substring(7), Size = size };
            }
            return null;
        }
    }
}
