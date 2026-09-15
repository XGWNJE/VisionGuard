using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Text;
using System.Windows.Forms;

namespace VisionGuard.WinFormsSmoke
{
    internal static class Program
    {
        [STAThread]
        private static int Main(string[] args)
        {
            if (args.Length < 3)
            {
                MessageBox.Show("用法：VisionGuard.WinFormsSmoke.exe <句柄文件> <图片1> <图片2> [...]",
                    "参数错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 2;
            }

            string handleFile = Path.GetFullPath(args[0]);
            string[] images = args.Skip(1).Select(Path.GetFullPath).ToArray();
            if (images.Length < 2 || images.Length > 16 || images.Any(path => !File.Exists(path)))
            {
                MessageBox.Show("必须提供 2–16 张存在的本地图片。", "参数错误",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 2;
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            try
            {
                Application.Run(new SmokeApplicationContext(handleFile, images));
                return 0;
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.ToString(), "测试窗口启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
        }
    }

    internal sealed class SmokeApplicationContext : ApplicationContext
    {
        private readonly string _handleFile;
        private readonly List<Form> _forms = new List<Form>();
        private int _shown;

        public SmokeApplicationContext(string handleFile, IEnumerable<string> imagePaths)
        {
            _handleFile = handleFile;
            string directory = Path.GetDirectoryName(handleFile);
            if (!string.IsNullOrEmpty(directory)) Directory.CreateDirectory(directory);
            int index = 0;
            var paths = imagePaths.ToArray();
            foreach (string imagePath in paths)
            {
                index++;
                Form form = CreateWindow(index, paths.Length, imagePath);
                form.Shown += OnShown;
                form.FormClosed += OnClosed;
                _forms.Add(form);
            }
            foreach (Form form in _forms) form.Show();
        }

        private static Form CreateWindow(int index, int total, string imagePath)
        {
            // 四路回归基线保持原来的 2 列 640×360；更多来源改用 3 列小窗口，
            // 否则第三行会超出常见屏幕高度，窗口落到屏幕外。
            int columns = total <= 4 ? 2 : 3;
            int clientWidth = total <= 4 ? 640 : 480;
            int clientHeight = total <= 4 ? 360 : 270;
            var form = new Form
            {
                Text = "VisionGuard WinForms Smoke " + index + " - " + Path.GetFileName(imagePath),
                StartPosition = FormStartPosition.Manual,
                Location = new Point(
                    60 + ((index - 1) % columns) * (clientWidth + 60),
                    60 + ((index - 1) / columns) * (clientHeight + 70)),
                ClientSize = new Size(clientWidth, clientHeight),
                MinimumSize = new Size(320, 240),
            };
            var picture = new PictureBox
            {
                Dock = DockStyle.Fill,
                SizeMode = PictureBoxSizeMode.Zoom,
                BackColor = Color.Black,
                Image = LoadUnlocked(imagePath),
            };
            form.Controls.Add(picture);
            form.FormClosed += (s, e) => picture.Image.Dispose();
            return form;
        }

        private static Image LoadUnlocked(string path)
        {
            using (var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
            using (var image = Image.FromStream(stream))
                return new Bitmap(image);
        }

        private void OnShown(object sender, EventArgs e)
        {
            _shown++;
            if (_shown != _forms.Count) return;
            string[] handles = _forms.Select(form => form.Handle.ToInt64().ToString(
                System.Globalization.CultureInfo.InvariantCulture)).ToArray();
            File.WriteAllLines(_handleFile, handles, new UTF8Encoding(false));
        }

        private void OnClosed(object sender, FormClosedEventArgs e)
        {
            _forms.Remove((Form)sender);
            if (_forms.Count == 0) ExitThread();
        }
    }
}
