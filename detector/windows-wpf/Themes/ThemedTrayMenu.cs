using System;
using System.Drawing;
using System.Windows.Forms;
using Media = System.Windows.Media;

namespace VisionGuard.Detector.Windows.Themes
{
    internal static class ThemedTrayMenu
    {
        internal static void Apply(ContextMenuStrip menu)
        {
            menu.Renderer = new ThemeRenderer();
            menu.ShowImageMargin = false;
            menu.Font = new Font("Microsoft YaHei UI", 10.5f);
            menu.Padding = new Padding(4);
            EventHandler refresh = (s, e) =>
            {
                menu.BackColor = BrushColor("SurfaceDark");
                menu.ForeColor = BrushColor("TextPrimary");
                foreach (ToolStripItem item in menu.Items)
                {
                    item.ForeColor = menu.ForeColor;
                    item.Padding = new Padding(8, 6, 8, 6);
                }
                menu.Invalidate();
            };
            menu.Opening += (s, e) => refresh(s, EventArgs.Empty);
            ThemeManager.Changed += refresh;
            menu.Disposed += (s, e) => ThemeManager.Changed -= refresh;
            refresh(menu, EventArgs.Empty);
        }

        private static Color BrushColor(string key)
        {
            var brush = System.Windows.Application.Current?.TryFindResource(key) as Media.SolidColorBrush;
            return brush == null ? SystemColors.Control : Color.FromArgb(brush.Color.A, brush.Color.R, brush.Color.G, brush.Color.B);
        }

        private sealed class ThemeRenderer : ToolStripProfessionalRenderer
        {
            internal ThemeRenderer() : base(new ThemeColors()) { }

            protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
            {
                e.TextColor = BrushColor(!e.Item.Enabled ? "TextSecondary" :
                    e.Item.Selected || e.Item.Pressed ? "SelectedTextBrush" : "TextPrimary");
                base.OnRenderItemText(e);
            }
        }

        private sealed class ThemeColors : ProfessionalColorTable
        {
            public override Color ToolStripDropDownBackground => BrushColor("SurfaceDark");
            public override Color MenuBorder => BrushColor("BorderBrush");
            public override Color MenuItemBorder => BrushColor("Primary");
            public override Color MenuItemSelected => BrushColor("SelectedBrush");
            public override Color MenuItemSelectedGradientBegin => MenuItemSelected;
            public override Color MenuItemSelectedGradientEnd => MenuItemSelected;
            public override Color MenuItemPressedGradientBegin => MenuItemSelected;
            public override Color MenuItemPressedGradientMiddle => MenuItemSelected;
            public override Color MenuItemPressedGradientEnd => MenuItemSelected;
            public override Color ImageMarginGradientBegin => ToolStripDropDownBackground;
            public override Color ImageMarginGradientMiddle => ToolStripDropDownBackground;
            public override Color ImageMarginGradientEnd => ToolStripDropDownBackground;
            public override Color SeparatorDark => BrushColor("BorderBrush");
            public override Color SeparatorLight => ToolStripDropDownBackground;
        }
    }
}
