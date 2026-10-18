using System.Reflection;
using System.Reflection.Metadata;
using System.Reflection.PortableExecutable;
using System.Reflection.Metadata.Ecma335;

var path = args.Length > 0 ? args[0] : throw new ArgumentException("assembly path");
using var fs = File.OpenRead(path);
using var pe = new PEReader(fs);
var md = pe.GetMetadataReader();

string TypeName(EntityHandle h) {
    if (h.Kind == HandleKind.TypeDefinition) {
        var t = md.GetTypeDefinition((TypeDefinitionHandle)h);
        var ns = md.GetString(t.Namespace);
        var n = md.GetString(t.Name);
        return string.IsNullOrEmpty(ns) ? n : ns + "." + n;
    }
    if (h.Kind == HandleKind.TypeReference) {
        var t = md.GetTypeReference((TypeReferenceHandle)h);
        var ns = md.GetString(t.Namespace);
        var n = md.GetString(t.Name);
        return string.IsNullOrEmpty(ns) ? n : ns + "." + n;
    }
    if (h.Kind == HandleKind.TypeSpecification) return "<TypeSpec>";
    return h.Kind.ToString();
}
string ReadType(ref BlobReader b) {
    var et = (SignatureTypeCode)b.ReadByte();
    return et switch {
        SignatureTypeCode.Void => "void",
        SignatureTypeCode.Boolean => "bool",
        SignatureTypeCode.Char => "char",
        SignatureTypeCode.SByte => "sbyte",
        SignatureTypeCode.Byte => "byte",
        SignatureTypeCode.Int16 => "short",
        SignatureTypeCode.UInt16 => "ushort",
        SignatureTypeCode.Int32 => "int",
        SignatureTypeCode.UInt32 => "uint",
        SignatureTypeCode.Int64 => "long",
        SignatureTypeCode.UInt64 => "ulong",
        SignatureTypeCode.Single => "float",
        SignatureTypeCode.Double => "double",
        SignatureTypeCode.String => "string",
        SignatureTypeCode.IntPtr => "nint",
        SignatureTypeCode.UIntPtr => "nuint",
        SignatureTypeCode.Object => "object",
        SignatureTypeCode.Pointer => ReadType(ref b) + "*",
        SignatureTypeCode.ByReference => ReadType(ref b) + "&",
        SignatureTypeCode.SZArray => ReadType(ref b) + "[]",
        SignatureTypeCode.Array => ReadArray(ref b),
        SignatureTypeCode.Class or SignatureTypeCode.ValueType => ReadTypeRef(ref b),
        SignatureTypeCode.GenericTypeParameter => "!" + b.ReadCompressedInteger(),
        SignatureTypeCode.GenericMethodParameter => "!!" + b.ReadCompressedInteger(),
        SignatureTypeCode.GenericTypeInstance => ReadGenericInst(ref b),
        SignatureTypeCode.FunctionPointer => ReadMethodSig(ref b),
        SignatureTypeCode.TypedReference => "typedref",
        SignatureTypeCode.RequiredModifier or SignatureTypeCode.OptionalModifier => (ReadTypeRef(ref b) + " mod(" + ReadType(ref b) + ")"),
        SignatureTypeCode.Pinned => ReadType(ref b) + " pinned",
        _ => "<ET_" + ((byte)et).ToString("X2") + ">"
    };
}
string ReadTypeRef(ref BlobReader b) {
    var idx = b.ReadCompressedInteger();
    var h = MetadataTokens.EntityHandle(CodedIndex.TypeDefOrRef.Decode(idx));
    return TypeName(h);
}
string ReadGenericInst(ref BlobReader b) {
    var kind = b.ReadByte();
    var baseName = ReadTypeRef(ref b);
    var n = b.ReadCompressedInteger();
    var args = new List<string>();
    for (var i = 0; i < n; i++) args.Add(ReadType(ref b));
    return baseName + "<" + string.Join(",", args) + ">";
}
string ReadArray(ref BlobReader b) {
    var elem = ReadType(ref b);
    var rank = b.ReadCompressedInteger();
    var nsizes = b.ReadCompressedInteger();
    for (var i = 0; i < nsizes; i++) _ = b.ReadCompressedInteger();
    var nlow = b.ReadCompressedInteger();
    for (var i = 0; i < nlow; i++) _ = b.ReadCompressedSignedInteger();
    return elem + "[" + new string(',', Math.Max(0, rank - 1)) + "]";
}
string ReadMethodSig(ref BlobReader b) {
    var cc = b.ReadByte();
    var gen = (cc & 0x10) != 0 ? b.ReadCompressedInteger() : 0;
    var n = b.ReadCompressedInteger();
    var ret = ReadType(ref b);
    var ps = new List<string>();
    for (var i = 0; i < n; i++) ps.Add(ReadType(ref b));
    return "fn(" + string.Join(", ", ps) + ")->" + ret;
}
string MethodSig(MethodDefinition m) {
    var b = md.GetBlobReader(m.Signature);
    var cc = b.ReadByte();
    var gen = (cc & 0x10) != 0 ? b.ReadCompressedInteger() : 0;
    var n = b.ReadCompressedInteger();
    var ret = ReadType(ref b);
    var ps = new List<string>();
    for (var i = 0; i < n; i++) ps.Add(ReadType(ref b));
    return "(" + string.Join(", ", ps) + ") -> " + ret;
}
string FieldSig(FieldDefinition f) {
    var b = md.GetBlobReader(f.Signature);
    _ = b.ReadByte();
    return ReadType(ref b);
}
string AttrName(CustomAttributeHandle ah) {
    var a = md.GetCustomAttribute(ah);
    var ctor = a.Constructor;
    if (ctor.Kind == HandleKind.MemberReference) {
        var mr = md.GetMemberReference((MemberReferenceHandle)ctor);
        return md.GetString(mr.Name);
    }
    if (ctor.Kind == HandleKind.MethodDefinition) return md.GetString(md.GetMethodDefinition((MethodDefinitionHandle)ctor).Name);
    return ctor.Kind.ToString();
}
string AttrBlob(CustomAttributeHandle ah) {
    var a = md.GetCustomAttribute(ah);
    var b = md.GetBlobReader(a.Value);
    return Convert.ToHexString(b.ReadBytes(b.Length));
}

Console.WriteLine("ASSEMBLY: " + path);
Console.WriteLine("TYPES");
foreach (var th in md.TypeDefinitions) {
    var t = md.GetTypeDefinition(th);
    var tn = TypeName(th);
    var attrs = string.Join(",", t.GetCustomAttributes().Select(AttrName));
    var isStruct = (t.Attributes & TypeAttributes.ClassSemanticsMask) == TypeAttributes.NotPublic && false;
    if (tn.Contains("Ocg", StringComparison.OrdinalIgnoreCase) || tn.Contains("Core", StringComparison.OrdinalIgnoreCase) || attrs.Contains("StructLayout")) {
        Console.WriteLine($"TYPE {tn} attrs=[{attrs}]");
        foreach (var fh in t.GetFields()) {
            var f = md.GetFieldDefinition(fh);
            Console.WriteLine($"  FIELD {md.GetString(f.Name)} : {FieldSig(f)} attrs=0x{((ushort)f.Attributes):X4} ca=[{string.Join(",", f.GetCustomAttributes().Select(AttrName))}]");
        }
    }
}
Console.WriteLine("PINVOKES");
foreach (var th in md.TypeDefinitions) {
    var t = md.GetTypeDefinition(th);
    var tn = TypeName(th);
    foreach (var mh in t.GetMethods()) {
        var m = md.GetMethodDefinition(mh);
        if ((m.Attributes & MethodAttributes.PinvokeImpl) == 0) continue;
        var imp = m.GetImport();
        var module = md.GetModuleReference(imp.Module);
        var name = md.GetString(m.Name);
        var native = md.GetString(imp.Name);
        Console.WriteLine($"PInvoke {tn}.{name} native={native} module={md.GetString(module.Name)} sig={MethodSig(m)} attrs=0x{((ushort)m.Attributes):X4} importAttrs=0x{((ushort)imp.Attributes):X4}");
    }
}
Console.WriteLine("METHODS-NAMES");
foreach (var th in md.TypeDefinitions) {
    var t = md.GetTypeDefinition(th);
    var tn = TypeName(th);
    if (tn.Contains("Ocg", StringComparison.OrdinalIgnoreCase) || tn.Contains("Core", StringComparison.OrdinalIgnoreCase) || tn.Contains("Dll", StringComparison.OrdinalIgnoreCase)) {
        foreach (var mh in t.GetMethods()) {
            var m = md.GetMethodDefinition(mh);
            Console.WriteLine($"{tn}.{md.GetString(m.Name)} {MethodSig(m)}");
        }
    }
}
