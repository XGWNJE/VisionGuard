// ┌─────────────────────────────────────────────────────────────┐
// │ StreamTargetService.cs                                      │
// │ 角色：推流态在本机选择目标推理节点（列候选 / 关联）          │
// │ 线程：同步阻塞调用，必须放在界面线程之外执行                 │
// │ 对外 API：Load()、Bind(targetDeviceId)，属性 Targets/BoundTargetId │
// └─────────────────────────────────────────────────────────────┘
using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using VisionGuard.Detector.Windows.Utils;

namespace VisionGuard.Detector.Windows.Services
{
    /// <summary>
    /// 推流目标：列出同账号里的推理节点，并把本节点关联到选中的目标上。
    /// 复用控制台与相机推流节点同一套接口（<c>/api/devices</c>、<c>/api/streams</c>、<c>/api/streams/bind</c>），
    /// 服务端只允许发布端为自身绑定，且目标必须是另一个可订阅的推理节点。
    /// </summary>
    public sealed class StreamTargetService
    {
        /// <summary>候选目标推理节点；离线与正在推流（同时不能订阅）的节点也要列出来，避免用户以为节点消失。</summary>
        public sealed class Target
        {
            public string DeviceId { get; set; } = "";
            public string DeviceName { get; set; } = "";
            public bool Online { get; set; }
            /// <summary>该节点自己正在推流：媒体方向互斥，它此刻收不下别人的画面。</summary>
            public bool Publishing { get; set; }

            public string DisplayName
            {
                get
                {
                    string name = string.IsNullOrEmpty(DeviceName) ? DeviceId : DeviceName;
                    // 模拟节点 / 用户命名本身就可能带「（离线）」，不要再叠一层。
                    if (Publishing) return name.Contains("（推流中") ? name : name + "（推流中·暂时收不了）";
                    if (Online) return name;
                    return name.EndsWith("（离线）", StringComparison.Ordinal) ? name : name + "（离线）";
                }
            }

            public override string ToString() => DisplayName;
        }

        private readonly string _serviceUrl, _token, _deviceId;

        public StreamTargetService(string serviceUrl, string token, string deviceId)
        {
            _serviceUrl = (serviceUrl ?? "").TrimEnd('/');
            _token = token ?? "";
            _deviceId = deviceId ?? "";
        }

        /// <summary>同账号中除自己以外的推理节点，在线的排在前面。</summary>
        public List<Target> Targets { get; } = new List<Target>();

        /// <summary>本节点当前的推流目标；未关联时为空串。</summary>
        public string BoundTargetId { get; private set; } = "";

        /// <summary>
        /// 拉取候选节点与本节点的绑定关系。返回可读错误文案，成功时为空串。
        /// </summary>
        public string Load()
        {
            if (string.IsNullOrEmpty(_deviceId) || string.IsNullOrEmpty(_token)) return "尚未登录账号，暂时无法选择推流目标。";
            string error = Request("/api/devices", null, out var devices);
            if (error.Length > 0) return error;
            error = Request("/api/streams", null, out var streams);
            if (error.Length > 0) return error;

            Targets.Clear();
            foreach (var item in Rows(devices, "devices"))
            {
                string component = SimpleJson.GetString(item, "component");
                if (component != "windows-inference" && component != "android-inference") continue;
                string id = SimpleJson.GetString(item, "deviceId");
                if (id.Length == 0 || id == _deviceId) continue;
                Targets.Add(new Target
                {
                    DeviceId = id,
                    DeviceName = SimpleJson.GetString(item, "deviceName"),
                    Online = SimpleJson.GetBool(item, "online", false),
                    Publishing = IsPushing(item),
                });
            }
            Targets.Sort((left, right) =>
            {
                if (left.Online != right.Online) return left.Online ? -1 : 1;
                return string.Compare(left.DeviceName, right.DeviceName, StringComparison.CurrentCulture);
            });

            BoundTargetId = "";
            foreach (var stream in Rows(streams, "streams"))
                if (SimpleJson.GetString(stream, "publisherDeviceId") == _deviceId)
                    BoundTargetId = SimpleJson.GetString(stream, "targetDeviceId");
            return "";
        }

        /// <summary>把本节点关联到指定目标节点；返回可读错误文案，成功时为空串。</summary>
        public string Bind(string targetDeviceId)
        {
            if (string.IsNullOrEmpty(_deviceId) || string.IsNullOrEmpty(_token)) return "尚未登录账号，暂时无法关联推流目标。";
            if (string.IsNullOrEmpty(targetDeviceId)) return "请先选择目标推理节点。";
            if (targetDeviceId == _deviceId) return "不能把推流目标设为本节点。";
            string error = Request("/api/streams/bind",
                SimpleJson.ToJson(new { publisherDeviceId = _deviceId, targetDeviceId }), out _);
            if (error.Length == 0) BoundTargetId = targetDeviceId;
            return error;
        }

        /// <summary>节点自报的推流态取自服务端组件状态词表；running / starting 表示它自己正在推流。</summary>
        private static bool IsPushing(Dictionary<string, object> device)
        {
            if (!device.TryGetValue("components", out object? value) || value is not Dictionary<string, object> components) return false;
            string state = SimpleJson.GetString(components, "publish");
            return state == "running" || state == "starting";
        }

        /// <summary>取响应里的数组字段；缺失或类型不符时返回空集合。</summary>
        private static List<Dictionary<string, object>> Rows(Dictionary<string, object> body, string key)
        {
            var rows = new List<Dictionary<string, object>>();
            if (!body.TryGetValue(key, out object? value) || value is not List<object> items) return rows;
            foreach (var item in items) if (item is Dictionary<string, object> row) rows.Add(row);
            return rows;
        }

        private string Request(string path, string json, out Dictionary<string, object> body)
        {
            body = new Dictionary<string, object>();
            try
            {
                ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
                using (var client = new HttpClient { Timeout = TimeSpan.FromSeconds(15) })
                {
                    if (!string.IsNullOrEmpty(_token)) client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", _token);
                    client.DefaultRequestHeaders.TryAddWithoutValidation("X-VisionGuard-Version", AppConfig.Version);
                    using (var response = json == null
                        ? client.GetAsync(_serviceUrl + path).GetAwaiter().GetResult()
                        : client.PostAsync(_serviceUrl + path, new StringContent(json, Encoding.UTF8, "application/json")).GetAwaiter().GetResult())
                    {
                        string text = response.Content.ReadAsStringAsync().GetAwaiter().GetResult();
                        body = SimpleJson.ParseDict(text);
                        if (response.IsSuccessStatusCode) return "";
                        if (response.StatusCode == HttpStatusCode.Unauthorized) return "登录已失效，请重新登录。";
                        if (response.StatusCode == HttpStatusCode.Forbidden) return "服务端不允许本节点关联推流目标。";
                        if (response.StatusCode == HttpStatusCode.NotFound) return "目标推理节点不可用或不在同一账号。";
                        if (response.StatusCode == HttpStatusCode.Conflict) return "该推理节点的来源已达上限。";
                        string reason = SimpleJson.GetString(body, "error");
                        return reason.Length > 0 ? reason : "服务请求失败（" + (int)response.StatusCode + "）。";
                    }
                }
            }
            catch (HttpRequestException) { return "无法连接统一服务，请检查网络与服务地址。"; }
            catch (System.Threading.Tasks.TaskCanceledException) { return "统一服务响应超时，请稍后重试。"; }
            catch (Exception ex) { return ex.Message; }
        }
    }
}
