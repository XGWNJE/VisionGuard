using System.Drawing;
using System.Windows.Forms;

namespace VisionGuard.UI
{
    /// <summary>
    /// Owner-Draw ListBox，按日志级别着色行文字。
    /// 继承 ListBox，可直接传给 LogManager(ListBox) 构造参数，无需修改 LogManager。
    /// </summary>
    public class OwnerDrawListBox : ListBox
    {
        private static readonly Color BgEven    = Color.FromArgb(21, 21, 21);
        private static readonly Color BgOdd     = Color.FromArgb(26, 26, 26);
        private static readonly Color BgSel     = Color.FromArgb(0, 84, 153);
        private static readonly Color FgNormal  = Color.FromArgb(204, 204, 204);
        private static readonly Color FgWarn    = Color.FromArgb(249, 199, 79);
        private static readonly Color FgError   = Color.FromArgb(240, 112, 112);
        private static readonly Color FgSel     = Color.White;

        public OwnerDrawListBox()
        {
            DrawMode            = DrawMode.OwnerDrawFixed;
            ItemHeight          = 18;
            BorderStyle         = BorderStyle.None;
            BackColor           = Color.FromArgb(15, 15, 15);
            ForeColor           = FgNormal;
            Font                = new Font("Consolas", 8f);
            SelectionMode       = SelectionMode.None;
        }

        protected override void OnDrawItem(DrawItemEventArgs e)
        {
            if (e.Index < 0) return;

            bool selected = (e.State & DrawItemState.Selected) != 0;
            Color bg = selected ? BgSel
                     : (e.Index % 2 == 0 ? BgEven : BgOdd);

            e.Graphics.FillRectangle(new SolidBrush(bg), e.Bounds);

            string text = Items[e.Index].ToString();
            Color fg;
            if      (text.Contains("[WARN]")) fg = FgWarn;
            else if (text.Contains("[ERR]"))  fg = FgError;
            else                              fg = selected ? FgSel : FgNormal;

            TextRenderer.DrawText(
                e.Graphics, text, Font, e.Bounds, fg,
                TextFormatFlags.Left |
                TextFormatFlags.VerticalCenter |
                TextFormatFlags.EndEllipsis);
        }
    }
}
