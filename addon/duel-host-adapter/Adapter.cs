using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Runtime.Loader;
using System.Text.Json;

// The supplied duel_host.dll owns processing, framing, prompts, retry, and response dispatch.
// Checked hooks export snapshots, retain MR3 and initialize AI from native pile sizes.
public static class MdproAdapter
{
    // The source-built Percy core uses the classic 3 + 7*n ConfirmCards packet.
    // The supplied Windows core has an extra byte; only the Linux host calls this hook.
    public static int LinuxConfirmCardsLength(ReadOnlySpan<byte> data)
    {
        if (data.IsEmpty || data[0] != 31) return -2;
        if (data.Length < 3) return -1;
        int length = 3 + 7 * data[2];
        return length <= data.Length ? length : -1;
    }

    static Assembly host = null!;
    static bool pendingHumanReply;
    [DllImport("ocgcore", CallingConvention = CallingConvention.Cdecl)]
    static extern int query_field_info(IntPtr duel, IntPtr buffer);
    [DllImport("ocgcore", CallingConvention = CallingConvention.Cdecl)]
    static extern int query_field_count(IntPtr duel, byte player, byte location);

    // Called after both decks have been loaded, before any opening draws. Never infer the
    // opponent's pile sizes from the AI's deck file, and never seed hidden card identities.
    public static void InitializeAi(object ai, IntPtr duel) => InitializeAiState(ai,
        query_field_count(duel, 0, 1), query_field_count(duel, 0, 64),
        query_field_count(duel, 1, 1), query_field_count(duel, 1, 64));

    public static void InitializeAiState(object ai, int main0, int extra0, int main1, int extra1)
    {
        Type type = ai.GetType();
        int seat = (int)type.GetProperty("Seat")!.GetValue(ai)!;
        object client = type.GetField("_client", BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(ai)!;
        using var stream = new MemoryStream();
        using var writer = new BinaryWriter(stream);
        writer.Write((byte)1); // STOC_GAME_MSG
        writer.Write((byte)4); // MSG_START
        writer.Write((byte)seat);
        writer.Write((byte)3); // match Native.StartDuel's Master Rule 3
        writer.Write(8000); writer.Write(8000);
        foreach (int count in new[] { main0, extra0, main1, extra1 }) {
            if (count < 0 || count > short.MaxValue) throw new InvalidDataException("Invalid initial pile size");
            writer.Write((short)count);
        }
        client.GetType().GetMethod("Feed")!.Invoke(client, new object[] { stream.ToArray() });
        type.GetField("<SeededCards>k__BackingField", BindingFlags.NonPublic | BindingFlags.Instance)!
            .SetValue(ai, seat == 0 ? main0 + extra0 : main1 + extra1);
        type.GetMethod("DrainResponses")!.Invoke(ai, null);
        Console.WriteLine(JsonSerializer.Serialize(new { type = "ai-state", seat, rule = 3, main0, extra0, main1, extra1 }));
    }

    public static int Main(string[] args)
    {
        string home = AppContext.BaseDirectory;
        AssemblyLoadContext.Default.Resolving += (_, name) => {
            string file = Path.Combine(home, name.Name + ".dll");
            return File.Exists(file) ? AssemblyLoadContext.Default.LoadFromAssemblyPath(file) : null;
        };
        host = AssemblyLoadContext.Default.LoadFromAssemblyPath(Path.Combine(home, "duel_host.dll"));
        return (int)host.EntryPoint!.Invoke(null, new object[] { args })!;
    }

    // Minecraft has already shuffled the decks, or arranged a training opening.
    public static int KeepOrder(Random random, int upperBound) => upperBound - 1;

    public static void MarkReply(object core)
    {
        if ((bool)core.GetType().GetField("_waiting", BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(core)!)
            pendingHumanReply = true;
    }

    // The example UI never sends arbitrary replies. Our server deliberately tests rejected/stale
    // packets as well: reissue the original request once instead of spinning on MSG_RETRY.
    public static bool HandleRetry(object core, byte[] message)
    {
        if (message.Length == 0 || message[0] != 1 || !pendingHumanReply) return false;
        pendingHumanReply = false;
        Type t = core.GetType();
        const BindingFlags flags = BindingFlags.NonPublic | BindingFlags.Instance;
        byte[] raw = (byte[])t.GetField("_lastPromptBytes", flags)!.GetValue(core)!;
        int length = (int)t.GetField("_lastPromptLength", flags)!.GetValue(core)!;
        var emit = host.GetType("DuelHostProgram")!.GetMethod("Emit", BindingFlags.NonPublic | BindingFlags.Static)!;
        void Send(object value) => emit.Invoke(null, new[] { value });
        var prompt = (Dictionary<string, object>)t.GetMethod("BuildPrompt", BindingFlags.NonPublic | BindingFlags.Static)!.Invoke(null, new object[] { raw, length })!;
        object gate = t.GetField("_gate", flags)!.GetValue(core)!;
        lock (gate) { t.GetField("_waiting", flags)!.SetValue(core, true); }
        var seq = t.GetField("_promptSeq", flags)!;
        long next = (long)seq.GetValue(core)! + 1;seq.SetValue(core, next);prompt["seq"] = next;
        Send(new { type = "message", function = 1, hex = "01", name = "Retry" });
        Send(prompt);
        var response = (System.Threading.ManualResetEventSlim)t.GetField("_response", flags)!.GetValue(core)!;
        response.Wait();response.Reset();
        return true;
    }

    // Called on the original processing thread just before emitting a prompt.
    public static void BeforeEmit(object value)
    {
        var prompt = value as Dictionary<string, object>;
        bool isPrompt = prompt != null && prompt.TryGetValue("type", out var type) && Equals(type, "prompt");
        bool visual = false;
        if (!isPrompt && value.GetType().GetProperty("type")?.GetValue(value) is string name && name == "message") {
            int function = Convert.ToInt32(value.GetType().GetProperty("function")!.GetValue(value));
            if (function != 1) pendingHumanReply = false;
            visual = function is 5 or 40 or 41 or 50 or 53 or 54 or 55 or 60 or 61 or 62 or 63 or 64 or 65 or 90 or 91 or 92 or 94 or 100 or 101 or 102 or 110 or 111;
        }
        if (!isPrompt && !visual) return;
        var snapshot = isPrompt ? prompt! : new Dictionary<string, object> { ["type"] = "snapshot" };
        Type program = host.GetType("DuelHostProgram", true)!;
        object core = program.GetField("_duel", BindingFlags.NonPublic | BindingFlags.Static)!.GetValue(null)!;
        Type coreType = core.GetType();
        var ptr = (IntPtr)coreType.GetField("_duel", BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(core)!;
        var query = program.GetNestedType("Native", BindingFlags.NonPublic)!.GetMethod("QueryField")!;
        IntPtr buffer = Marshal.AllocHGlobal(0x40000);
        try
        {
            byte[] Read(int count) {
                if (count < 0 || count > 0x40000) throw new InvalidDataException("Invalid snapshot size");
                byte[] bytes = new byte[count]; Marshal.Copy(buffer, bytes, 0, count); return bytes;
            }
            byte[] field = Read(query_field_info(ptr, buffer));
            if (field.Length < 2 || field[0] != 162 || field[1] != 3)
                throw new InvalidDataException("Reference host did not enter Master Rule 3");
            snapshot["field"] = Convert.ToHexString(field);
            var board = new List<object>();
            foreach (int player in new[] { 0, 1 })
            foreach (int location in new[] { 1, 2, 4, 8, 16, 32, 64 }) {
                string Fetch(int flags) => Convert.ToHexString(Read((int)query.Invoke(null,
                    new object[] { ptr, player, location, flags, buffer, 0 })!));
                board.Add(new { player, location, cards = Fetch(0x60033B), counters = Fetch(0x20003) });
            }
            snapshot["board"] = board;
            snapshot["adapter"] = "mdpromod-0.1.0-mr3-v1";
            if (!isPrompt) { Console.WriteLine(JsonSerializer.Serialize(snapshot)); Console.Out.Flush(); }
        }
        finally { Marshal.FreeHGlobal(buffer); }
    }
}
