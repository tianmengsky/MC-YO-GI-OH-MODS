"""
P0 第 6 步：逐行插桩，精确定位 0xC0000409 落在哪一次原生调用上。
每一步都 flush 输出，这样崩溃前最后一行就是"罪魁"。
"""
import ctypes
import os
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


def say(msg):
    print(msg, flush=True)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "legacy7"

    say("[1] 打开脚本包与卡库 ...")
    zf = zipfile.ZipFile(SCRIPT_ZIP)
    con = sqlite3.connect(DB)
    cols = [r[1] for r in con.execute("PRAGMA table_info(datas)")]
    cards = {r[0]: r for r in con.execute("SELECT * FROM datas")}
    say(f"    卡库 {len(cards)} 条，脚本 {len(zf.namelist())} 个")

    keep = {}
    stats = {"card": 0, "script": 0}

    def sr(name, out_len):
        key = name.decode("latin1") if isinstance(name, bytes) else name
        try:
            data = zf.read("script/" + key)
        except KeyError:
            out_len[0] = 0
            return None
        stats["script"] += 1
        buf = (ctypes.c_ubyte * len(data)).from_buffer_copy(data)
        keep[key] = buf
        out_len[0] = len(data)
        return ctypes.cast(buf, ctypes.c_void_p).value

    def cr(code, ptr):
        row = cards.get(code)
        if row is None:
            return 0
        stats["card"] += 1
        g = lambda c: row[cols.index(c)] or 0
        vals = [g("id"), g("alias"), g("setcode") & 0xFFFFFFFFFFFFFFFF, g("type"),
                g("level"), g("attribute"), g("race"), g("atk"), g("def"), 0, 0, 0, 0]
        blob = b"".join(int(v & 0xFFFFFFFFFFFFFFFF).to_bytes(8, "little") for v in vals)
        ctypes.memmove(ptr, blob[:48], 48)      # 只写 48 字节，留足余量
        return 1

    def mh(t, d):
        pass

    say("[2] 加载 DLL ...")
    core = ctypes.WinDLL(DLL)
    say("    OK")

    a, b, c = SR_T(sr), CR_T(cr), MH_T(mh)
    keep.update(a=a, b=b, c=c)
    say("[3] 注册回调 ...")
    core.set_script_reader.argtypes = [SR_T]; core.set_script_reader(a)
    core.set_card_reader.argtypes = [CR_T];   core.set_card_reader(b)
    core.set_message_handler.argtypes = [MH_T]; core.set_message_handler(c)
    say("    OK")

    say("[4] create_duel ...")
    core.create_duel.restype = ctypes.c_void_p
    core.create_duel.argtypes = [ctypes.c_uint32]
    duel = core.create_duel(ctypes.c_uint32(7))
    say(f"    handle={duel}")
    if not duel:
        return 1

    say("[5] set_player_info ...")
    core.set_player_info.argtypes = [ctypes.c_void_p] + [ctypes.c_int] * 4
    core.set_player_info(duel, 0, 8000, 5, 1)
    say("    player0 OK")
    core.set_player_info(duel, 1, 8000, 5, 1)
    say("    player1 OK")

    say("[6] 取卡组 ...")
    c2 = sqlite3.connect(DB); c2.execute("ATTACH DATABASE ? AS pk", (PACK_DB,))
    deck = [r[0] for r in c2.execute(
        """SELECT d.id FROM datas d JOIN pk.pack p ON d.id=p.id
           WHERE p.date<='2017-03-24' AND (d.type&1) AND (d.type&0x10) AND d.atk>0
           ORDER BY d.atk DESC LIMIT 40""")]
    c2.close()
    say(f"    {len(deck)} 张，前三张 = {deck[:3]}")

    core.new_card.restype = None
    if mode == "legacy7":
        core.new_card.argtypes = [ctypes.c_void_p, ctypes.c_uint32,
                                  ctypes.c_uint8, ctypes.c_uint8,
                                  ctypes.c_uint8, ctypes.c_uint8, ctypes.c_uint8]
        say("[7] new_card 7 参数(legacy) ... 第 1 张")
        core.new_card(duel, deck[0], 0, 0, 1, 0, 8)
        say("    第 1 张 OK")
        say("    接着放第 2 张")
        core.new_card(duel, deck[1], 0, 0, 1, 1, 8)
        say("    第 2 张 OK")
    elif mode == "struct2":
        core.new_card.argtypes = [ctypes.c_void_p, ctypes.c_void_p]

        class NewCardInfo(ctypes.Structure):
            _pack_ = 1
            _fields_ = [("team", ctypes.c_uint32), ("duelist", ctypes.c_uint32),
                        ("code", ctypes.c_uint32), ("con", ctypes.c_uint8),
                        ("loc", ctypes.c_uint8), ("seq", ctypes.c_uint8),
                        ("pos", ctypes.c_uint8)]
        say(f"[7] new_card 2 参数(struct, size={ctypes.sizeof(NewCardInfo)}) ...")
        info = NewCardInfo(0, 0, deck[0], 0, 1, 0, 8)
        core.new_card(duel, ctypes.byref(info))
        say("    OK")

    say("[8] start_duel ...")
    core.start_duel.argtypes = [ctypes.c_void_p, ctypes.c_int]
    core.start_duel(duel, 0)
    say("    OK")

    say("[9] process 一次 ...")
    core.process.restype = ctypes.c_int
    core.process.argtypes = [ctypes.c_void_p]
    st = core.process(duel)
    say(f"    process 返回 {st}")

    say("[10] get_message ...")
    core.get_message.restype = ctypes.c_uint32
    core.get_message.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ubyte)]
    buf = (ctypes.c_ubyte * 0x20000)()
    n = core.get_message(duel, buf)
    say(f"    消息总长 {n} 字节")

    core.end_duel.argtypes = [ctypes.c_void_p]
    core.end_duel(duel)
    say(f"[DONE] 全流程走通  card_reader={stats['card']} script_reader={stats['script']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
