using System;
using System.Reflection;
using System.Runtime.Loader;

public static class LinuxHostProtocolSmoke
{
    delegate int FrameLength(ReadOnlySpan<byte> bytes);
    public static void Main(string[] args)
    {
        var host = AssemblyLoadContext.Default.LoadFromAssemblyPath(System.IO.Path.GetFullPath(args[0]));
        var frame = host.GetType("ClassicMessageSplitter", true)!.GetMethod("MessageLength")!.CreateDelegate<FrameLength>();
        void Check(byte[] bytes, int expected)
        { if (frame(bytes) != expected) throw new Exception("Incorrect frame length for " + Convert.ToHexString(bytes)); }
        // Exact failing packet from the phone: reveal the card searched by Sangan.
        var reveal = Convert.FromHexString("1F010180DF6E05000203");
        Check(reveal, 10);
        Check(Convert.FromHexString("1F010180DF6E050002032800"), 10); // next turn in same batch
        Check(Convert.FromHexString("1F010280DF6E0500020380DF6E05000204"), 17);
        Check(Convert.FromHexString("1F010280DF6E05000203"), -1); // truncated second card
        Check(new byte[] {31, 1}, -1);
        Check(new byte[] {31, 1, 0}, 3);
        Check(new byte[] {40, 0}, 2); // other packets still use the original parser
        Check(new byte[] {41, 0, 1}, 3);
        Console.WriteLine("PASS Linux host framing: captured ConfirmCards, multiple cards, batched messages, truncation, fallback");
    }
}
