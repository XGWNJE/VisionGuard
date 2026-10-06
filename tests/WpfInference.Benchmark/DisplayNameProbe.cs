using System;
using VisionGuard.Detector.Windows.Utils;
using VisionGuard.Detector.Windows.Models;
using VisionGuard.Detector.Windows.Services;

internal static class DisplayNameProbe
{
    internal static int Run()
    {
        try
        {
            foreach (string name in new[] { new string('门', 64), new string('A', 64), string.Concat(System.Linq.Enumerable.Repeat("😀", 32)) })
            {
                if (DisplayNamePolicy.Normalize(name) != name) throw new Exception("Valid name was truncated");
                var source = new MonitorSource("front", name, "", new MonitorConfig());
                if (source.SourceName != name) throw new Exception("Source creation lost a valid name");
            }
            foreach (string name in new[] { new string('门',65), string.Concat(System.Linq.Enumerable.Repeat("😀",33)), "  ", "门\n", "a\0b" })
            {
                if (DisplayNamePolicy.IsValid(name)) throw new Exception("Invalid name was accepted");
                bool rejected=false;
                try { new MonitorSource("front",name,"",new MonitorConfig()); } catch(ArgumentException) { rejected=true; }
                if (!rejected) throw new Exception("Source creation accepted an invalid name");
            }
            if(DisplayNamePolicy.Normalize("  门厅  ")!="门厅")throw new Exception("Whitespace normalization failed");
            foreach(string placeholder in new[] { "Default string", "SYSTEM PRODUCT NAME", "To be filled by O.E.M.", "\n " })
                if(DisplayNamePolicy.DeviceModel(placeholder)!="Windows电脑")throw new Exception("BIOS placeholder was used as a model");
            if(DisplayNamePolicy.DeviceModel(new string('A',47)+"😀").Length!=47 || DisplayNamePolicy.DeviceModel(" MI\n6X ")!="MI 6X")throw new Exception("Model boundary failed");
            Console.WriteLine("PASS Windows device/source UTF-16 boundaries and invalid-source rejection");
            return 0;
        }
        catch(Exception error){Console.Error.WriteLine(error.Message);return 1;}
    }
}
