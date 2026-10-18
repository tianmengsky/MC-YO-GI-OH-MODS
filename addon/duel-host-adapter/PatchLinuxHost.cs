using System;
using System.Linq;
using Mono.Cecil;
using Mono.Cecil.Cil;

// Input is the already reviewed MR3/snapshot/retry-patched Windows host.
// Linux's source-built Percy core has the classic ConfirmCards wire layout.
public static class PatchLinuxHost
{
    public static void Main(string[] args)
    {
        using var host = AssemblyDefinition.ReadAssembly(args[0]);
        using var adapter = AssemblyDefinition.ReadAssembly(args[1]);
        var module = host.MainModule;
        module.Architecture = TargetArchitecture.I386;
        module.Attributes = (module.Attributes | ModuleAttributes.ILOnly)
            & ~(ModuleAttributes.Required32Bit | ModuleAttributes.Preferred32Bit);
        var splitter = module.Types.Single(t => t.Name == "ClassicMessageSplitter");
        var original = splitter.Methods.Single(m => m.Name == "MessageLength");
        original.Name = "WindowsMessageLength";
        var wrapper = new MethodDefinition("MessageLength", original.Attributes, original.ReturnType);
        wrapper.Parameters.Add(new ParameterDefinition("data", ParameterAttributes.None, original.Parameters[0].ParameterType));
        wrapper.Body.InitLocals = true;
        wrapper.Body.Variables.Add(new VariableDefinition(module.TypeSystem.Int32));
        var il = wrapper.Body.GetILProcessor();
        var fallback = il.Create(OpCodes.Ldarg_0);
        il.Append(il.Create(OpCodes.Ldarg_0));
        var hook = adapter.MainModule.Types.Single(t => t.Name == "MdproAdapter").Methods.Single(m => m.Name == "LinuxConfirmCardsLength");
        il.Append(il.Create(OpCodes.Call, module.ImportReference(hook)));
        il.Append(il.Create(OpCodes.Stloc_0));
        il.Append(il.Create(OpCodes.Ldloc_0));
        il.Append(il.Create(OpCodes.Ldc_I4, -2));
        il.Append(il.Create(OpCodes.Beq, fallback));
        il.Append(il.Create(OpCodes.Ldloc_0));
        il.Append(il.Create(OpCodes.Ret));
        il.Append(fallback);
        il.Append(il.Create(OpCodes.Call, original));
        il.Append(il.Create(OpCodes.Ret));
        int calls = 0;
        foreach (var method in splitter.Methods.Where(m => m.HasBody))
            foreach (var instruction in method.Body.Instructions)
                if (instruction.Operand is MethodReference reference && reference.DeclaringType == splitter
                    && (reference == original || reference.Name == "MessageLength"))
                { instruction.Operand = wrapper; calls++; Console.WriteLine("Redirected " + method.Name); }
        if (calls != 2) throw new InvalidOperationException("Expected Split/SelfTest calls, got " + calls);
        splitter.Methods.Add(wrapper);
        // SetDllDirectory is Windows-only. Linux resolves libraries via LD_LIBRARY_PATH.
        var sqlite = module.Types.Single(t => t.Name == "DuelHostProgram").NestedTypes.Single(t => t.Name == "Sqlite");
        var cctor = sqlite.Methods.Single(m => m.IsConstructor && m.IsStatic);
        if (cctor.Body.Instructions.Count(i => i.Operand is MethodReference r && r.Name == "SetDllDirectory") != 1)
            throw new InvalidOperationException("Unexpected Sqlite initializer");
        cctor.Body.Instructions.Clear();
        cctor.Body.GetILProcessor().Append(Instruction.Create(OpCodes.Ret));
        host.Write(args[2]);
        Console.WriteLine("Linux host: AnyCPU, classic ConfirmCards framing, portable Sqlite initializer");
    }
}
