"""
P0 第 5 步：定位 0xC0000409 (STATUS_STACK_BUFFER_OVERRUN)。

假设：MDPro3 这份 ocgcore 的 card_data 结构体比 EDOPro 标准的 56 字节小，
      我们的 card_reader 回调写超了内核的栈缓冲，触发 GS 栈保护 → __fastfail。

做法：让 card_reader 只写前 N 字节，二分找出"写到多大就不崩"。
      每个 N 在独立子进程里跑。
"""
import ctypes
import sqlite3
import sys
import zipfile

DLL = r"F:\MyCardLibrary\mdpro3\MDPro3_Data\Plugins\x86_64\ocgcore.dll"
DB = r"F:\MyCardLibrary\mdpro3\Data\locales\zh-CN\cards.cdb"
PACK_DB = r"F:\MyCardLibrary\mdpro3\Data\pack\pack.db"
SCRIPT_ZIP = r"F:\MyCardLibrary\mdpro3\Data\script.zip"

SR_T = ctypes.CFUNCTYPE(ctypes.c_void_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_int))
CR_T = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_uint32, ctypes.c_void_p)
MH_T = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_void_p)

_zip = zipfile.ZipFile(SCRIPT_ZIP)
_con = sqlite3.connect(DB)
_cols = [r[1] for r in _con.execute("PRAGMA table_info(datas)")]
_cards = {r[0]: r for r in _con.execute("SELECT * FROM datas")}
_keep, _stats = {}, {"card": 0, "script": 0}


def sr(name, out_len):
    key = name.decode("latin1") if isinstance(name, bytes) else name
    try:
        data = _zip.read("script/" + key)
    except KeyError:
        out_len[0] = 0
        return None
    _stats["script"] += 1
    buf = (ctypes.c_ubyte * len(data)).from_buffer_copy(data)
    _keep[key] = buf
    out_len[0] = len(data)
    return ctypes.cast(buf, ctypes.c_void_p).value


def make_cr(write_bytes):
    def cr(code, ptr):
        row = _cards.get(code)
        if row is None:
            return 0
        _stats["card"] += 1
        if write_bytes <= 0:
            return 1                                  # 完全不写
        g = lambda c: row[_cols.index(c)] or 0
        vals = [g("id"), g("alias"), g("setcode") & 0xFFFFFFFFFFFFFFFF, g("type"),
                g("level"), g("attribute"), g("race"), g("atk"), g("def"),
                0, 0, 0, 0]
        blob = bytearray()
        for v in vals:
            blob += int(v & 0xFFFFFFFFFFFFFFFF).to_bytes(8, "little", signed=False)
        raw = bytes(blob[:write_bytes])
        ctypes.memmove(ptr, raw, len(raw))
        return 1
    return cr


def mh(t, d):
    pass


def run(mode):
    if mode.startswith("w"):
        write_bytes = int(mode[1:])
        struct2 = False
    elif mode == "nowrite":
        write_bytes, struct2 = 0, False
    elif mode == "struct2":
        write_bytes, struct2 = 40, True
    else:
        print("RESULT unknown mode"); return 2

    core = ctypes.WinDLL(DLL)
    a, b, c = SR_T(sr), CR_T(make_cr(write_bytes)), MH_T(mh)
    _keep.update(a=a, b=b, c=c)
    core.set_script_reader.argtypes = [SR_T]; core.set_script_reader(a)
    core.set_card_reader.argtypes = [CR_T];   core.set_card_reader(b)
    core.set_message_handler.argtypes = [MH_T]; core.set_message_handler(c)

    core.create_duel.restype = ctypes.c_void_p
    core.create_duel.argtypes = [ctypes.c_uint32]
    duel = core.create_duel(ctypes.c_uint32(7))
    if not duel:
        print("RESULT no duel"); return 1

    core.set_player_info.argtypes = [ctypes.c_void_p] + [ctypes.c_int] * 4
    core.set_player_info(duel, 0, 8000, 5, 1)
    core.set_player_info(duel, 1, 8000, 5, 1)

    con = sqlite3.connect(DB); con.execute("ATTACH DATABASE ? AS pk", (PACK_DB,))
    deck = [r[0] for r in con.execute(
        """SELECT d.id FROM datas d JOIN pk.pack p ON d.id=p.id
           WHERE p.date<='2017-03-24' AND (d.type&1) AND (d.type&0x10) AND d.atk>0
           ORDER BY d.atk DESC LIMIT 40""")]
    con.close()

    if struct2:
        # 试探 OCG_NewCardInfo { u32 team; u32 duelist; u32 code; u8 con,loc,seq,pos }
        core.new_card.restype = None
        core.new_card.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
        for p in (0, 1):
            for seq, code in enumerate(deck):
                info = (ctypes.c_uint32 * 3)(p, p, code) + \
                       (ctypes.c_ubyte * 4)(0, 1, seq, 8)
                core.new_card(duel, ctypes.cast(info, ctypes.c_void_p))
    else:
        core.new_card.restype = None
        core.new_card.argtypes = [ctypes.c_void_p, ctypes.c_uint32,
                                  ctypes.c_uint8, ctypes.c_uint8,
                                  ctypes.c_uint8, ctypes.c_uint8, ctypes.c_uint8]
        for p in (0, 1):
            for seq, code in enumerate(deck):
                core.new_card(duel, code, p, p, 1, seq, 8)

    core.start_duel.argtypes = [ctypes.c_void_p, ctypes.c_int]
    core.start_duel(duel, 0)

    core.process.restype = ctypes.c_int
    core.process.argtypes = [ctypes.c_void_p]
    core.get_message.restype = ctypes.c_uint32
    core.get_message.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ubyte)]
    core.set_responsei.argtypes = [ctypes.c_void_p, ctypes.c_int]
    core.set_responseb.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ubyte)]

    buf = (ctypes.c_ubyte * 0x20000)()
    types = {}
    for _ in range(200):
        st = core.process(duel)
        while True:
            n = core.get_message(duel, buf)
            if n == 0:
                break
            off = 0
            while off + 4 <= n:
                ml = int.from_bytes(bytes(buf[off:off + 4]), "little")
                if ml < 1 or off + 4 + ml > n:
                    break
                types[bytes(buf[off + 4])[0]] = types.get(bytes(buf[off + 4])[0], 0) + 1
                t = bytes(buf[off + 4])[0]
                if t in (10, 11, 12, 13, 14, 16, 18, 19, 20, 22, 23, 24, 25, 26, 100, 101, 103):
                    core.set_responsei(duel, 0)
                elif t in (15, 21, 27, 30, 31, 32, 33, 102):
                    core.set_responseb(duel, (ctypes.c_ubyte * 4)())
                off += 4 + ml
        if st:
            break

    core.end_duel.argtypes = [ctypes.c_void_p]
    core.end_duel(duel)
    print(f"RESULT {mode} SURVIVED card={_stats['card']} script={_stats['script']} "
          f"msgtypes={sorted(types.items(), key=lambda x:-x[1])[:8]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(run(sys.argv[1]))
