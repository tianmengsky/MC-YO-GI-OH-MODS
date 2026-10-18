"""
P0 第 4 步（关键里程碑）：真正跑起一场对局。

路径：create_duel(uint32 seed) + set_player_info + new_card + start_duel
      + process/get_message 循环。

成功判据：
  - card_reader 被命中（内核真的在查卡）
  - script_reader 被命中（内核真的在加载 Lua）
  - 收到 MSG_DRAW / MSG_NEW_TURN / MSG_NEW_PHASE 等对局推进类消息
"""
import ctypes
import sqlite3
import sys
import zipfile

DLL = r"F:\MyCardLibrary\mdpro3\MDPro3_Data\Plugins\x86_64\ocgcore.dll"
DB = r"F:\MyCardLibrary\mdpro3\Data\locales\zh-CN\cards.cdb"
PACK_DB = r"F:\MyCardLibrary\mdpro3\Data\pack\pack.db"
SCRIPT_ZIP = r"F:\MyCardLibrary\mdpro3\Data\script.zip"

# --- 位置 / 表示形式（ygopro 常量）---
LOCATION_DECK, LOCATION_HAND, LOCATION_MZONE, LOCATION_SZONE = 0x01, 0x02, 0x04, 0x08
LOCATION_GRAVE, LOCATION_REMOVED, LOCATION_EXTRA = 0x10, 0x20, 0x40
POS_FACEUP_ATTACK, POS_FACEDOWN_DEFENSE = 0x1, 0x8

# --- 消息类型（节选，够 P0 看清流程）---
MSG = {
    1: "RETRY", 2: "HINT", 3: "WAITING", 4: "START", 5: "WIN",
    10: "SELECT_BATTLECMD", 11: "SELECT_IDLECMD", 12: "SELECT_EFFECTYN",
    13: "SELECT_YESNO", 14: "SELECT_OPTION", 15: "SELECT_CARD",
    16: "SELECT_CHAIN", 18: "SELECT_PLACE", 19: "SELECT_POSITION",
    20: "SELECT_TRIBUTE", 21: "SELECT_COUNTER", 22: "SELECT_SUM",
    23: "SELECT_DISFIELD", 24: "SORT_CARD", 25: "SELECT_UNSELECT_CARD",
    26: "CONFIRM_DECKTOP", 27: "CONFIRM_CARDS", 30: "SHUFFLE_DECK",
    31: "SHUFFLE_HAND", 32: "REFRESH_DECK", 33: "SWAP_GRAVE_DECK",
    40: "DRAW", 41: "DAMAGE", 42: "RECOVER", 43: "EQUIP",
    50: "SUMMONING", 51: "SUMMONED", 52: "SPSUMMONING", 53: "SPSUMMONED",
    54: "FLIPSUMMONING", 55: "FLIPSUMMONED", 60: "CHAINING", 61: "CHAINED",
    62: "CHAIN_SOLVING", 63: "CHAIN_SOLVED", 64: "CHAIN_END", 65: "CHAIN_NEGATED",
    70: "POS_CHANGE", 71: "SET", 81: "NEW_TURN", 82: "NEW_PHASE",
    83: "MOVE", 86: "ATTACK", 87: "BATTLE", 90: "DECK_TOP",
    100: "ANNOUNCE_RACE", 101: "ANNOUNCE_ATTRIB", 102: "ANNOUNCE_CARD",
    103: "ANNOUNCE_NUMBER", 110: "CARD_HINT", 111: "PLAYER_HINT",
    120: "MATCH_KILL", 121: "TAG_SWAP", 130: "RELOAD_FIELD",
}
# 需要应答、且用 set_responsei 的消息 → 直接把回应内容设为多少个 int
RESP_INT = {
    10: 1, 11: 1, 12: 1, 13: 1, 14: 1, 16: 1, 18: 1, 19: 1, 20: 2,
    22: 2, 23: 1, 24: 1, 25: 1, 100: 1, 101: 1, 103: 1, 26: 1,
}
RESP_BUF = {15: 1, 21: 1, 27: 1, 102: 1, 30: 0, 31: 0, 32: 0, 33: 0}

SR_T = ctypes.CFUNCTYPE(ctypes.c_void_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_int))
CR_T = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_uint32, ctypes.c_void_p)
MH_T = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_void_p)


class CardData(ctypes.Structure):
    _fields_ = [("code", ctypes.c_uint32), ("alias", ctypes.c_uint32),
                ("setcode", ctypes.c_uint64), ("type", ctypes.c_uint32),
                ("level", ctypes.c_uint32), ("attribute", ctypes.c_uint32),
                ("race", ctypes.c_uint32), ("attack", ctypes.c_int32),
                ("defense", ctypes.c_int32), ("lscale", ctypes.c_uint32),
                ("rscale", ctypes.c_uint32), ("link_marker", ctypes.c_uint32),
                ("rule_code", ctypes.c_uint32)]


class Env:
    def __init__(self):
        self.zip = zipfile.ZipFile(SCRIPT_ZIP)
        self.con = sqlite3.connect(DB)
        self.cols = [r[1] for r in self.con.execute("PRAGMA table_info(datas)")]
        self.cards = {r[0]: r for r in self.con.execute("SELECT * FROM datas")}
        self.keep = {}
        self.hits = {"script": 0, "card": 0, "card_miss": 0, "msg": 0}
        self.scripts_used = set()

    def script_reader(self, name, out_len):
        key = name.decode("latin1") if isinstance(name, bytes) else name
        try:
            data = self.zip.read("script/" + key)
        except KeyError:
            out_len[0] = 0
            return None
        self.hits["script"] += 1
        self.scripts_used.add(key)
        buf = (ctypes.c_ubyte * len(data)).from_buffer_copy(data)
        self.keep[key] = buf
        out_len[0] = len(data)
        return ctypes.cast(buf, ctypes.c_void_p).value

    def card_reader(self, code, ptr):
        row = self.cards.get(code)
        if row is None:
            self.hits["card_miss"] += 1
            return 0
        self.hits["card"] += 1
        d = ctypes.cast(ptr, ctypes.POINTER(CardData)).contents
        g = lambda c: row[self.cols.index(c)]
        d.code, d.alias = g("id"), g("alias")
        d.setcode = g("setcode") & 0xFFFFFFFFFFFFFFFF
        d.type, d.level = g("type"), g("level")
        d.attribute, d.race = g("attribute"), g("race")
        d.attack, d.defense = g("atk"), g("def")
        return 1

    def msg_handler(self, mtype, data):
        self.hits["msg"] += 1


def pick_deck(env, player_seed, size=40):
    """从 <=2017-03-24 的卡池里挑普通怪兽，尽量少依赖复杂脚本。
    pack 表在独立的 pack.db 中，需 ATTACH。"""
    con = sqlite3.connect(DB)
    con.execute(r"ATTACH DATABASE ? AS pk", (PACK_DB,))
    q = """SELECT d.id FROM datas d JOIN pk.pack p ON d.id = p.id
           WHERE p.date <= '2017-03-24'
             AND (d.type & 1) AND (d.type & 0x10)
             AND d.atk > 0
           ORDER BY d.atk DESC LIMIT ?"""
    rows = [r[0] for r in con.execute(q, (size,))]
    con.close()
    assert len(rows) == size, f"卡池查询只取到 {len(rows)} 张"
    return rows


def main():
    print("=" * 72)
    print("P0-4  跑起一场真实对局")
    print("=" * 72)

    env = Env()
    core = ctypes.WinDLL(DLL)

    sr, cr, mh = SR_T(env.script_reader), CR_T(env.card_reader), MH_T(env.msg_handler)
    env.keep.update(sr=sr, cr=cr, mh=mh)
    core.set_script_reader.argtypes = [SR_T]; core.set_script_reader(sr)
    core.set_card_reader.argtypes = [CR_T];   core.set_card_reader(cr)
    core.set_message_handler.argtypes = [MH_T]; core.set_message_handler(mh)
    print("[回调] script/card/message reader 已注册")

    core.create_duel.restype = ctypes.c_void_p
    core.create_duel.argtypes = [ctypes.c_uint32]
    duel = core.create_duel(ctypes.c_uint32(0x9E3779B9))
    print(f"[create_duel] handle = {duel}")
    assert duel, "创建对局失败"

    core.set_player_info.argtypes = [ctypes.c_void_p] + [ctypes.c_int] * 4
    for p in (0, 1):
        core.set_player_info(duel, p, 8000, 5, 1)
    print("[set_player_info] 双方 LP=8000 / 起手5 / 每回合抽1")

    core.new_card.restype = None
    core.new_card.argtypes = [ctypes.c_void_p, ctypes.c_uint32,
                              ctypes.c_uint8, ctypes.c_uint8,
                              ctypes.c_uint8, ctypes.c_uint8, ctypes.c_uint8]
    decks = []
    for p in (0, 1):
        deck = pick_deck(env, p)
        decks.append(deck)
        for seq, code in enumerate(deck):
            core.new_card(duel, code, p, p, LOCATION_DECK, seq, POS_FACEDOWN_DEFENSE)
    print(f"[new_card] 双方各放入 {len(decks[0])} 张卡（<=2017 普通怪兽）")

    core.start_duel.restype = None
    core.start_duel.argtypes = [ctypes.c_void_p, ctypes.c_int]
    core.start_duel(duel, 0)
    print("[start_duel] 已开始\n")

    core.process.restype = ctypes.c_int
    core.process.argtypes = [ctypes.c_void_p]
    core.get_message.restype = ctypes.c_uint32
    core.get_message.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ubyte)]
    core.set_responsei.restype = None
    core.set_responsei.argtypes = [ctypes.c_void_p, ctypes.c_int]
    core.set_responseb.restype = None
    core.set_responseb.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ubyte)]

    buf = (ctypes.c_ubyte * 0x20000)()
    log = []
    seen_types = {}
    steps = 0
    MAX_STEPS = 4000

    while steps < MAX_STEPS:
        steps += 1
        status = core.process(duel)

        while True:
            n = core.get_message(duel, buf)
            if n == 0:
                break
            # 缓冲区里是若干条消息：[uint32 len][byte type][payload...]
            off = 0
            while off + 4 <= n:
                mlen = int.from_bytes(bytes(buf[off:off + 4]), "little")
                if mlen < 1 or off + 4 + mlen > n:
                    break
                body = bytes(buf[off + 4: off + 4 + mlen])
                mtype = body[0]
                seen_types[mtype] = seen_types.get(mtype, 0) + 1
                if len(log) < 60:
                    log.append((steps, MSG.get(mtype, f"#{mtype}"), mlen, body[:24].hex()))
                # 需要应答就回一个最保守的答案
                if mtype in RESP_INT:
                    core.set_responsei(duel, 0)
                elif mtype in RESP_BUF:
                    z = (ctypes.c_ubyte * max(1, RESP_BUF[mtype] * 4))()
                    core.set_responseb(duel, z)
                off += 4 + mlen

        if status:
            print(f"[process] 第 {steps} 步返回 1 → 对局结束")
            break

    core.end_duel.argtypes = [ctypes.c_void_p]
    core.end_duel(duel)

    print("--- 前 60 条消息 ---")
    for s, name, mlen, hexs in log:
        print(f"  step{s:<5} {name:<22} len={mlen:<4} {hexs}")
    print("\n--- 消息类型统计（次数降序）---")
    for t, c in sorted(seen_types.items(), key=lambda x: -x[1])[:25]:
        print(f"  {MSG.get(t, '#' + str(t)):<24} x{c}")
    print(f"\n--- 回调统计 ---")
    print(f"  card_reader  命中 {env.hits['card']} 次，未命中 {env.hits['card_miss']} 次")
    print(f"  script_reader 命中 {env.hits['script']} 次，涉及 {len(env.scripts_used)} 个脚本")
    print(f"  已加载脚本样例: {sorted(env.scripts_used)[:10]}")

    ok = env.hits["card"] > 0 and env.hits["script"] > 0 and 40 in seen_types
    print("\n>>> P0-4 " + ("通过：内核在本进程内成功读取卡库、加载 Lua、并推进了对局。"
                          if ok else "未通过：见上方统计。"))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
