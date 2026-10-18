using System;
using System.Collections;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.Loader;
using System.Text.Json;

// Replays production engine events through the actual embedded Windbot, without native code.
public static class AiStateSmoke
{
    static readonly BindingFlags Private = BindingFlags.NonPublic | BindingFlags.Instance;
    static object Get(object value, string name) => value.GetType().GetProperty(name)!.GetValue(value)!;
    static void Check(bool ok, string text) { if (!ok) throw new Exception(text); }
    public static void Main(string[] args)
    {
        string home = Path.GetFullPath(args[0]);
        AssemblyLoadContext.Default.Resolving += (_, name) => {
            string path = Path.Combine(home, name.Name + ".dll");
            return File.Exists(path) ? AssemblyLoadContext.Default.LoadFromAssemblyPath(path) : null;
        };
        var host = AssemblyLoadContext.Default.LoadFromAssemblyPath(Path.Combine(home, "duel_host.dll"));
        var adapter = AssemblyLoadContext.Default.LoadFromAssemblyPath(Path.Combine(home, "adapter.dll"));
        var init = adapter.GetType("MdproAdapter")!.GetMethod("InitializeAiState")!;
        var options = host.GetType("WindBotOptions", true)!;
        var aiType = host.GetType("WindBotAi", true)!;
        object Create(int seat, string deck) => Activator.CreateInstance(aiType,
            new[] { Activator.CreateInstance(options, new object[] {seat, "Regression", "AI_Test", deck, "default"})! })!;
        object State(object ai) => Get(Get(aiType.GetField("_client", Private)!.GetValue(ai)!, "Behavior"), "Duel");
        var feed = aiType.GetMethod("FeedMessage")!;
        string fixture = Path.GetFullPath(args[1]);
        Directory.SetCurrentDirectory(Path.GetFullPath(args[2]));
        bool baseline = args.Contains("--baseline");
        int[] counts = { 40, 15, 60, 0 };
        foreach (int seat in new[] {0, 1}) {
            var ai = Create(seat, Path.Combine(fixture, seat + ".ydk"));
            init.Invoke(null, new object[] {ai, counts[0], counts[1], counts[2], counts[3]});
            var state = State(ai);
            Check((bool)Get(state, "IsFirst") == (seat == 0), "AI seat mapping");
            Check(!(bool)Get(state, "IsNewRule") && !(bool)Get(state, "IsNewRule2020"), "AI must use MR3");
            var fields = (Array)Get(state, "Fields");
            for (int player = 0; player < 2; player++) {
                var field = fields.GetValue(seat == 0 ? player : 1 - player)!;
                foreach (var pair in new[] { ("Deck", counts[player*2]), ("ExtraDeck", counts[player*2+1]) }) {
                    var pile = (IList)Get(field, pair.Item1);
                    Check(pile.Count == pair.Item2, "Authoritative asymmetric pile count");
                    Check(pile.Cast<object>().All(card => (int)Get(card, "Id") == 0), "Initial hidden identities must stay unknown");
                }
            }
            // Every extra address used by the real failure must exist for either AI seat.
            byte[] move = Convert.FromHexString("3235D09003004004080004000400080000");
            feed.Invoke(ai, new object[] {move, move.Length});
            byte[] summoning = Convert.FromHexString("3E35D0900300040004");
            feed.Invoke(ai, new object[] {summoning, summoning.Length});
            feed.Invoke(ai, new object[] {new byte[]{63}, 1});
            var field0 = fields.GetValue(seat == 0 ? 0 : 1)!;
            var summoned = ((IList)Get(field0, "MonsterZone"))[0]!;
            Check((int)Get(summoned, "Id") == 59822133 && (bool)Get(summoned, "IsSpecialSummoned"), "Captured special summon state");
            Console.WriteLine("PASS asymmetric 40/60 main, 15/0 extra, MR3, hidden identities and captured summon: AI seat " + seat);
        }
        var replayAi = Create(1, Path.Combine(fixture, "1.ydk"));
        if (!baseline) init.Invoke(null, new object[] {replayAi, 40, 15, 40, 0});
        else {
            // The old host's fake startup duplicates the AI's 40/0 for both players.
            init.Invoke(null, new object[] {replayAi, 40, 0, 40, 0});
        }
        int messages = 0;
        foreach (string line in File.ReadLines(Path.Combine(fixture, "host.jsonl"))) {
            if (!line.StartsWith("{")) continue;
            using var json = JsonDocument.Parse(line);
            var row = json.RootElement;
            if (row.GetProperty("type").GetString() != "message") continue;
            byte[] bytes = Convert.FromHexString(row.GetProperty("hex").GetString()!);
            if (bytes.Length == 0 || bytes[0] == 1) continue;
            int kind = bytes[0];
            if (new[]{10,11,12,13,14,15,16,18,19,20,21,22,23,24,25,26,140,141,142,143,144}.Contains(kind) && bytes[1] != 1) continue;
            if (kind == 6 && bytes[1] != 1) {
                if (bytes[2] == 1 || bytes[2] == 64) continue;
                if (bytes[2] != 16) for (int k = 3; k + 4 <= bytes.Length;) {
                    int n = BitConverter.ToInt32(bytes, k);
                    if (n < 4 || n > bytes.Length-k) throw new Exception("Invalid query record");
                    if (n >= 16 && (bytes[k+15]&5) == 0) Array.Clear(bytes, k+4, n-4);
                    k += n;
                }
            }
            try { feed.Invoke(replayAi, new object[] {bytes, bytes.Length}); }
            catch (TargetInvocationException e) { throw new Exception("Replay failed at message " + messages + " " + Convert.ToHexString(bytes), e.InnerException); }
            aiType.GetMethod("DrainResponses")!.Invoke(replayAi, null);
            messages++;
        }
        Check(messages > 50, "Missing captured production trace");
        Console.WriteLine("PASS production trace replay: " + messages + " engine messages, " + fixture);
    }
}
