"""
P0 第 7 步：用正确的 new_card(duel, OCG_NewCardInfo*) 放满 40 张牌，再 start_duel。

同时把 card_data 的写入大小做成参数，验证回调结构体是否真的会溢出。
"""
import ctypes
import sqlite3
import sys
import zipfile

DLL = r"F:\Minecarft\游戏王mod工作区\inputs\mdpro3\MDPro3_Data\Plugins\x86_64\ocgcore.dll"
DB = r"F:\Minecarft\游戏王mod工作区\inputs\mdpro3\Data\locales\zh-CN\cards.cdb"
PACK_DB = r"F:\Minecarft\游戏王mod工作区\inputs\mdpro3\Data\pack\pack.db"
SCRIPT_ZIP = r"F:\Minecarft\游戏王mod工作区\inputs\mdpro3\Data\script.zip"

SR_T = ctypes.CFUNCTYPE(ctypes.c_void_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_int))
CR_T = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_uint32, ctypes.c_void_p)
MH_T = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_void_p)

MSG = {1: "RETRY", 2: "HINT", 3: "WAITING", 4: "START", 5: "WIN", 10: "SELECT_BATTLECMD",
       11: "SELECT_IDLECMD", 12: "SELECT_EFFECTYN", 13: "SELECT_YESNO", 14: "SELECT_OPTION",
       15: "SELECT_CARD", 16: "SELECT_CHAIN", 18: "SELECT_PLACE", 19: "SELECT_POSITION",
       20: "SELECT_TRIBUTE", 21: "SELECT_COUNTER", 22: "SELECT_SUM", 23: "SELECT_DISFIELD",
       24: "SORT_CARD", 25: "SELECT_UNSELECT_CARD", 26: "CONFIRM_DECKTOP", 27: "CONFIRM_CARDS",
       30: "SHUFFLE_DECK", 31: "SHUFFLE_HAND", 32: "REFRESH_DECK", 33: "SWAP_GRAVE_DECK",
       40: "DRAW", 41: "DAMAGE", 42: "RECOVER", 43: "EQUIP", 50: "SUMMONING",
       51: "SUMMONED", 52: "SPSUMMONING", 53: "SPSUMMONED", 54: "FLIPSUMMONING",
       55: "FLIPSUMMONED", 60: "CHAINING", 61: "CHAINED", 62: "CHAIN_SOLVING",
       63: "CHAIN_SOLVED", 64: "CHAIN_END", 70: "POS_CHANGE", 71: "SET",
       81: "NEW_TURN", 82: "NEW_PHASE", 83: "MOVE", 86: "ATTACK", 87: "BATTLE",
       90: "DECK_TOP", 100: "ANNOUNCE_RACE", 101: "ANNOUNCE_ATTRIB", 102: "ANNOUNCE_CARD",
       103: "ANNOUNCE_NUMBER", 110: "CARD_HINT", 111: "PLAYER_HINT", 120: "MATCH_KILL",
       130: "RELOAD_FIELD"}

RESP_INT = (10, 11, 12, 13, 14, 16, 18, 19, 20, 22, 23, 24, 25, 26, 100, 101, 103)
RESP_BUF = (15, 21, 27, 30, 31, 32, 33, 102)


class NewCardInfo(ctypes.Structure):
    _fields_ = [("team", ctypes.c_uint32), ("duelist", ctypes.c_uint32),
                ("code", ctypes.c_uint32), ("con", ctypes.c_uint8),
                ("loc", ctypes.c_uint8), ("seq", ctypes.c_uint8),
                ("pos", ctypes.c_uint8)]


def main():
    write_bytes = int(sys.argv[1]) if len(sys.argv) > 1 else 40
    print(f"card_data 写入字节数 = {write_bytes}", flush=True)

    zf = zipfile.ZipFile(SCRIPT_ZIP)
    con = sqlite3.connect(DB)
    cols = [r[1] for r in con.execute("PRAGMA table_info(datas)")]
    cards = {r[0]: r for r in con.execute("SELECT * FROM datas")}
    keep, stats = {}, {"card": 0, "script": 0, "miss": 0}

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
            stats["miss"] += 1
            return 0
        stats["card"] += 1
        g = lambda c: row[cols.index(c)] or 0
        vals = [g("id"), g("alias"), g("setcode") & 0xFFFFFFFFFFFFFFFF, g("type"),
                g("level"), g("attribute"), g("race"), g("atk"), g("def"), 0, 0, 0, 0]
        blob = b"".join(int(v & 0xFFFFFFFFFFFFFFFF).to_bytes(8, "little") for v in vals)
        n = min(write_bytes, len(blob), 56)
        ctypes.memmove(ptr, blob[:n], n)
        return 1

    def mh(t, d):
        pass

    core = ctypes.WinDLL(DLL)
    a, b, c = SR_T(sr), CR_T(cr), MH_T(mh)
    keep.update(a=a, b=b, c=c)
    core.set_script_reader.argtypes = [SR_T]; core.set_script_reader(a)
    core.set_card_reader.argtypes = [CR_T];   core.set_card_reader(b)
    core.set_message_handler.argtypes = [MH_T]; core.set_message_handler(c)

    core.create_duel.restype = ctypes.c_void_p
    core.create_duel.argtypes = [ctypes.c_uint32]
    duel = core.create_duel(ctypes.c_uint32(0x9E3779B9))
    print(f"create_duel -> {duel}", flush=True)

    core.set_player_info.argtypes = [ctypes.c_void_p] + [ctypes.c_int] * 4
    core.set_player_info(duel, 0, 8000, 5, 1)
    core.set_player_info(duel, 1, 8000, 5, 1)

    c2 = sqlite3.connect(DB); c2.execute("ATTACH DATABASE ? AS pk", (PACK_DB,))
    decks = [[r[0] for r in c2.execute(
        """SELECT d.id FROM datas d JOIN pk.pack p ON d.id=p.id
           WHERE p.date<='2017-03-24' AND (d.type&1) AND (d.type&0x10) AND d.atk>0
           ORDER BY d.atk DESC LIMIT 40""")] for _ in (0, 1)]
    c2.close()

    core.new_card.restype = None
    core.new_card.argtypes = [ctypes.c_void_p, ctypes.POINTER(NewCardInfo)]
    for p in (0, 1):
        for seq, code in enumerate(decks[p]):
            info = NewCardInfo(p, p, code, p, 1, seq, 8)
            core.new_card(duel, ctypes.byref(info))
    print(f"new_card x{len(decks[0])*2} 完成", flush=True)

    core.start_duel.argtypes = [ctypes.c_void_p, ctypes.c_int]
    core.start_duel(duel, 0)
    print("start_duel 完成", flush=True)

    core.process.restype = ctypes.c_int
    core.process.argtypes = [ctypes.c_void_p]
    core.get_message.restype = ctypes.c_uint32
    core.get_message.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ubyte)]
    core.set_responsei.argtypes = [ctypes.c_void_p, ctypes.c_int]
    core.set_responseb.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ubyte)]

    buf = (ctypes.c_ubyte * 0x40000)()
    types, log = {}, []
    for step in range(600):
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
                body = bytes(buf[off + 4:off + 4 + ml])
                t = body[0]
                types[t] = types.get(t, 0) + 1
                if len(log) < 40:
                    log.append((step, MSG.get(t, f"#{t}"), ml, body[:20].hex()))
                if t in RESP_INT:
                    core.set_responsei(duel, 0)
                elif t in RESP_BUF:
                    core.set_responseb(duel, (ctypes.c_ubyte * 4)())
                off += 4 + ml
        if st:
            print(f"process 第 {step} 步返回 1（对局结束）", flush=True)
            break

    core.end_duel.argtypes = [ctypes.c_void_p]
    core.end_duel(duel)

    print("\n--- 前 40 条消息 ---", flush=True)
    for s, nm, ml, hx in log:
        print(f"  step{s:<4} {nm:<20} len={ml:<4} {hx}")
    print("\n--- 消息类型 Top 20 ---", flush=True)
    for t, cn in sorted(types.items(), key=lambda x: -x[1])[:20]:
        print(f"  {MSG.get(t, '#' + str(t)):<22} x{cn}")
    print(f"\ncard_reader 命中 {stats['card']} 次 / 未命中 {stats['miss']} 次", flush=True)
    print(f"script_reader 命中 {stats['script']} 次", flush=True)

    ok = stats["card"] > 0 and stats["script"] > 0 and 40 in types
    print(f"\n>>> P0-7 {'通过' if ok else '未通过'}", flush=True)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
