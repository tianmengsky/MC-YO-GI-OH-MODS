using System;
using System.Linq;
using Mono.Cecil;
using Mono.Cecil.Cil;

// Deliberately narrow, fail-closed patches against the SHA-pinned example assembly.
public static class PatchHost
{
    public static void Main(string[] args)
    {
        using var host = AssemblyDefinition.ReadAssembly(args[0]);
        using var adapter = AssemblyDefinition.ReadAssembly(args[1]);
        var program = host.MainModule.Types.Single(t => t.Name == "DuelHostProgram");
        var native = program.NestedTypes.Single(t => t.Name == "Native");
        var core = program.NestedTypes.Single(t => t.Name == "DuelCore");
        var start = native.Methods.Single(m => m.Name == "StartDuel");
        var argument = start.Body.Instructions.Single(i => i.OpCode == OpCodes.Ldarg_1);
        argument.OpCode = OpCodes.Ldc_I4; argument.Operand = 3 << 16;
        var hook = adapter.MainModule.Types.Single(t => t.Name == "MdproAdapter");
        var ai = host.MainModule.Types.Single(t => t.Name == "WindBotAi");
        var fakeStart = ai.Methods.Single(m => m.Name == "FeedGameStart");
        if (ai.Methods.SelectMany(m => m.HasBody ? m.Body.Instructions : Enumerable.Empty<Instruction>())
            .Count(i => i.Operand is MethodReference r && r.FullName == fakeStart.FullName) != 1)
            throw new InvalidOperationException("Unexpected AI startup call sites");
        fakeStart.Body = new MethodBody(fakeStart);
        fakeStart.Body.GetILProcessor().Append(Instruction.Create(OpCodes.Ret));
        var coreCtor = core.Methods.Single(m => m.IsConstructor && !m.IsStatic);
        var aiNew = coreCtor.Body.Instructions.Single(i => i.OpCode == OpCodes.Newobj &&
            i.Operand is MethodReference r && r.DeclaringType.FullName == ai.FullName);
        var ctorIl = coreCtor.Body.GetILProcessor();
        var afterAi = aiNew.Next;
        ctorIl.InsertBefore(afterAi, ctorIl.Create(OpCodes.Dup));
        ctorIl.InsertBefore(afterAi, ctorIl.Create(OpCodes.Ldarg_0));
        ctorIl.InsertBefore(afterAi, ctorIl.Create(OpCodes.Ldfld, core.Fields.Single(f => f.Name == "_duel")));
        ctorIl.InsertBefore(afterAi, ctorIl.Create(OpCodes.Call, host.MainModule.ImportReference(hook.Methods.Single(m => m.Name == "InitializeAi"))));
        var emit = program.Methods.Single(m => m.Name == "Emit");
        var il = emit.Body.GetILProcessor();
        var first = emit.Body.Instructions[0];
        il.InsertBefore(first, il.Create(OpCodes.Ldarg_0));
        il.InsertBefore(first, il.Create(OpCodes.Call, host.MainModule.ImportReference(hook.Methods.Single(m => m.Name == "BeforeEmit"))));
        var deck = core.Methods.Single(m => m.Name == "AddDeck");
        var next = deck.Body.Instructions.Single(i => i.OpCode == OpCodes.Callvirt &&
            i.Operand is MethodReference m && m.DeclaringType.FullName == "System.Random" && m.Name == "Next");
        next.OpCode = OpCodes.Call;
        next.Operand = host.MainModule.ImportReference(hook.Methods.Single(m => m.Name == "KeepOrder"));
        foreach (string name in new[] { "SetInteger", "SetBytes" }) {
            var method = core.Methods.Single(m => m.Name == name);
            var processor = method.Body.GetILProcessor();var begin = method.Body.Instructions[0];
            processor.InsertBefore(begin, processor.Create(OpCodes.Ldarg_0));
            processor.InsertBefore(begin, processor.Create(OpCodes.Call, host.MainModule.ImportReference(hook.Methods.Single(m => m.Name == "MarkReply"))));
        }
        var message = core.Methods.Single(m => m.Name == "EmitMessage");
        var messageIl = message.Body.GetILProcessor();var messageBegin = message.Body.Instructions[0];
        messageIl.InsertBefore(messageBegin, messageIl.Create(OpCodes.Ldarg_0));
        messageIl.InsertBefore(messageBegin, messageIl.Create(OpCodes.Ldarg_1));
        messageIl.InsertBefore(messageBegin, messageIl.Create(OpCodes.Call, host.MainModule.ImportReference(hook.Methods.Single(m => m.Name == "HandleRetry"))));
        messageIl.InsertBefore(messageBegin, messageIl.Create(OpCodes.Brfalse, messageBegin));
        messageIl.InsertBefore(messageBegin, messageIl.Create(OpCodes.Ret));
        host.Write(args[2]);
        Console.WriteLine("Applied checked patches: MR3, snapshot, deck order, human retry recovery, authoritative AI pile sizes and MR3 startup");
    }
}
