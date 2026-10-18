"""
P0 第 8 步：用权威结构体定义，实测 new_card / card_data 的四种组合。

  ncA = OCG_NewCardInfo   {u8 team; u8 duelist; u32 code; u8 con; u32 loc; u32 seq; u32 pos}  24B
  ncB = legacy new_card_info {u32 code; u8 owner; u8 player; u8 loc; u8 seq; u8 pos}          9B
  cdA = OCG_CardData      (setcodes 是指针, race 是 u64)                                     64B
  cdB = legacy card_data  (setcode 是 u64, race 是 u32)                                      40B
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

MSG = {1: "RETRY", 3: "WAITING", 4: "START", 5: "WIN", 11: "SELECT_IDLECMD", 13: "SELECT_YESNO",
       15: "SELECT_CARD", 16: "SELECT_CHAIN", 18: "SELECT_PLACE", 19: "SELECT_POSITION",
       40: "DRAW", 41: "DAMAGE", 81: "NEW_TURN", 82: "NEW_PHASE", 83: "MOVE", 86: "ATTACK",
       87: "BATTLE", 90: "DECK_TOP", 100: "ANNOUNCE_RACE", 101: "ANNOUNCE_ATTRIB",
       103: "ANNOUNCE_NUMBER", 110: "CARD_HINT", 111: "PLAYER_HINT"}


class NC_A(ctypes.Structure):
    _fields_ = [("team", ctypes.c_uint8), ("duelist", ctypes.c_uint8),
                ("code", ctypes.c_uint32), ("con", ctypes.c_uint8),
                ("loc", ctypes.c_uint32), ("seq", ctypes.c_uint32),
                ("pos", ctypes.c_uint32)]


class NC_B(ctypes.Structure):
    _fields_ = [("code", ctypes.c_uint32), ("owner", ctypes.c_uint8),
                ("player", ctypes.c_uint8), ("location", ctypes.c_uint8),
                ("sequence", ctypes.c_uint8), ("position", ctypes.c_uint8)]


class CD_A(ctypes.Structure):
    _fields_ = [("code", ctypes.c_uint32), ("alias", ctypes.c_uint32),
                ("setcodes", ctypes.POINTER(ctypes.c_uint16)), ("type", ctypes.c_uint32),
                ("level", ctypes.c_uint32), ("attribute", ctypes.c_uint32),
                ("race", ctypes.c_uint64), ("attack", ctypes.c_int32),
                ("defense", ctypes.c_int32), ("lscale", ctypes.c_uint32),
                ("rscale", ctypes.c_uint32), ("link_marker", ctypes.c_uint32)]


class CD_B(ctypes.Structure):
    _fields_ = [("code", ctypes.c_uint32), ("alias", ctypes.c_uint32),
                ("setcode", ctypes.c_uint64), ("type", ctypes.c_uint32),
                ("level", ctypes.c_uint32), ("attribute", ctypes.c_uint32),
                ("race", ctypes.c_uint32), ("attack", ctypes.c_int32),
                ("defense", ctypes.c_int32)]


def main():
    nc_name, cd_name = sys.argv[1], sys.argv[2]
    print(f"组合: new_card={nc_name} ({ctypes.sizeof(NC_A if nc_name=='ncA' else NC_B)}B)  "
          f"card_data={cd_name} ({ctypes.sizeof(CD_A if cd_name=='cdA' else CD_B)}B)", flush=True)

    zf = zipfile.ZipFile(SCRIPT_ZIP)
    con = sqlite3.connect(DB)
    cols = [r[1] for r in con.execute("PRAGMA table_info(datas)")]
    cards = {r[0]: r for r in con.execute("SELECT * FROM datas")}
    keep, stats = {}, {"card": 0, "script": 0, "miss": 0}
    sc_pool = []            # setcodes 指针的保活池

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
        if cd_name == "cdA":
            d = ctypes.cast(ptr, ctypes.POINTER(CD_A)).contents
            sc = (ctypes.c_uint16 * 2)(g("setcode") & 0xFFFF, 0)
            sc_pool.append(sc)
            d.setcodes = ctypes.cast(sc, ctypes.POINTER(ctypes.c_uint16))
            d.code, d.alias, d.type = g("id"), g("alias"), g("type")
            d.level, d.attribute = g("level"), g("attribute")
            d.race, d.attack, d.defense = g("race"), g("atk"), g("def")
            d.lscale = d.rscale = d.link_marker = 0
        else:
            d = ctypes.cast(ptr, ctypes.POINTER(CD_B)).contents
            d.code, d.alias = g("id"), g("alias")
            d.setcode = g("setcode") & 0xFFFFFFFFFFFFFFFF
            d.type, d.level = g("type"), g("level")
            d.attribute, d.race = g("attribute"), g("race")
            d.attack, d.defense = g("atk"), g("def")
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
    deck = [r[0] for r in c2.execute(
        """SELECT d.id FROM datas d JOIN pk.pack p ON d.id=p.id
           WHERE p.date<='2017-03-24' AND (d.type&1) AND (d.type&0x10) AND d.atk>0
           ORDER BY d.atk DESC LIMIT 40""")]
    c2.close()

    NC = NC_A if nc_name == "ncA" else NC_B
    core.new_card.restype = None
    core.new_card.argtypes = [ctypes.c_void_p, ctypes.POINTER(NC)]
    for p in (0, 1):
        for seq, code in enumerate(deck):
            if nc_name == "ncA":
                info = NC(p, p, code, p, 1, seq, 8)
            else:
                info = NC(code, p, p, 1, seq, 8)
            core.new_card(duel, ctypes.byref(info))

    print(f"new_card x80 完成  card_reader 已被调用 {stats['card']} 次 / 未命中 {stats['miss']} 次",
          flush=True)

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
    types = {}
    for step in range(800):
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
                t = bytes(buf[off + 4])
                types[t] = types.get(t, 0) + 1
                if t in (10, 11, 12, 13, 14, 16, 18, 19, 20, 22, 23, 24, 25, 26, 100, 101, 103):
                    core.set_responsei(duel, 0)
                elif t in (15, 21, 27, 30, 31, 32, 33, 102):
                    core.set_responseb(duel, (ctypes.c_ubyte * 4)())
                off += 4 + ml
        if st:
            print(f"process 第 {step} 步返回 1（对局结束）", flush=True)
            break

    core.end_duel.argtypes = [ctypes.c_void_p]
    core.end_duel(duel)

    top = sorted(types.items(), key=lambda x: -x[1])[:10]
    print(f"RESULT {nc_name}_{cd_name} OK card={stats['card']} script={stats['script']} "
          f"types={[(MSG.get(t,'#'+str(t)), n) for t, n in top]}", flush=True)
    ok = stats["card"] > 0 and stats["script"] > 0 and 40 in types
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
