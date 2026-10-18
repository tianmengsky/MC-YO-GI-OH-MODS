"""
P0 第 2 步：注册宿主回调，让内核真正具备"读卡 + 读脚本"的能力。

step1 证明了 create_duel_v2 单独调用会崩（回调指针为空）。
本步先试 legacy 路径：set_script_reader / set_card_reader / set_message_handler
注册到全局，然后 create_duel_v2。

若本步通过，说明可以完全绕开 OCG_DuelOptions 结构体布局这个未知数。
"""
import ctypes
import os
import sys
import zipfile
import sqlite3

DLL = r"F:\Minecarft\游戏王mod工作区\inputs\mdpro3\MDPro3_Data\Plugins\x86_64\ocgcore.dll"
DB = r"F:\Minecarft\游戏王mod工作区\inputs\mdpro3\Data\locales\zh-CN\cards.cdb"
SCRIPT_ZIP = r"F:\Minecarft\游戏王mod工作区\inputs\mdpro3\Data\script.zip"

# ---------------------------------------------------------------------------
# 回调签名（依据 ocgapi.h 的 legacy 定义）
#   byte* script_reader(const char* name, int* len)
#   int   card_reader(uint32_t code, card_data* data)
#   void  message_handler(int type, void* data)
# ---------------------------------------------------------------------------
# 注意：ctypes 的 CFUNCTYPE 不允许指针作为返回值类型，故用 c_void_p 代替 byte*
SCRIPT_READER = ctypes.CFUNCTYPE(ctypes.c_void_p,
                                 ctypes.c_char_p, ctypes.POINTER(ctypes.c_int))
CARD_READER = ctypes.CFUNCTYPE(ctypes.c_int,
                               ctypes.c_uint32, ctypes.c_void_p)
MSG_HANDLER = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_void_p)


class CardData(ctypes.Structure):
    """EDOPro ocgapi.h 的 card_data 布局"""
    _fields_ = [
        ("code", ctypes.c_uint32),
        ("alias", ctypes.c_uint32),
        ("setcode", ctypes.c_uint64),
        ("type", ctypes.c_uint32),
        ("level", ctypes.c_uint32),
        ("attribute", ctypes.c_uint32),
        ("race", ctypes.c_uint32),
        ("attack", ctypes.c_int32),
        ("defense", ctypes.c_int32),
        ("lscale", ctypes.c_uint32),
        ("rscale", ctypes.c_uint32),
        ("link_marker", ctypes.c_uint32),
        ("rule_code", ctypes.c_uint32),
    ]


# 全局持有，防止被 GC 回收导致野指针
_keepalive = {}
_script_zip = zipfile.ZipFile(SCRIPT_ZIP)
_con = sqlite3.connect(DB)
_datas_cols = [r[1] for r in _con.execute("PRAGMA table_info(datas)")]
_cards = {row[0]: row for row in _con.execute("SELECT * FROM datas")}
_missing_scripts = set()
_hits = {"script": 0, "card": 0, "msg": 0}


def _script_reader(name, out_len):
    raw = name if isinstance(name, bytes) else name.encode()
    key = raw.decode("latin1")
    try:
        data = _script_zip.read("script/" + key)
        _hits["script"] += 1
    except KeyError:
        _missing_scripts.add(key)
        out_len[0] = 0
        return None
    buf = (ctypes.c_ubyte * len(data)).from_buffer_copy(data)
    _keepalive[key] = buf          # 必须保活：内核会直接读这块内存
    out_len[0] = len(data)
    return ctypes.cast(buf, ctypes.c_void_p).value


def _card_reader(code, data_ptr):
    row = _cards.get(code)
    if row is None:
        return 0
    _hits["card"] += 1
    d = ctypes.cast(data_ptr, ctypes.POINTER(CardData)).contents
    d.code = row[_datas_cols.index("id")]
    d.alias = row[_datas_cols.index("alias")]
    d.setcode = row[_datas_cols.index("setcode")] & 0xFFFFFFFFFFFFFFFF
    d.type = row[_datas_cols.index("type")]
    d.level = row[_datas_cols.index("level")]
    d.attribute = row[_datas_cols.index("attribute")]
    d.race = row[_datas_cols.index("race")]
    d.attack = row[_datas_cols.index("atk")]
    d.defense = row[_datas_cols.index("def")]
    return 1


def _msg_handler(mtype, data):
    _hits["msg"] += 1


def main():
    print("=" * 70)
    print("P0-2  legacy 回调注册路径")
    print("=" * 70)

    core = ctypes.WinDLL(DLL)

    sr = SCRIPT_READER(_script_reader)
    cr = CARD_READER(_card_reader)
    mh = MSG_HANDLER(_msg_handler)
    _keepalive["sr"], _keepalive["cr"], _keepalive["mh"] = sr, cr, mh

    core.set_script_reader.restype = None
    core.set_script_reader.argtypes = [SCRIPT_READER]
    core.set_card_reader.restype = None
    core.set_card_reader.argtypes = [CARD_READER]
    core.set_message_handler.restype = None
    core.set_message_handler.argtypes = [MSG_HANDLER]

    core.set_script_reader(sr)
    print("[set_script_reader]   已注册")
    core.set_card_reader(cr)
    print("[set_card_reader]     已注册")
    core.set_message_handler(mh)
    print("[set_message_handler] 已注册")

    # 顺便验证 script_reader 回调本身能不能工作
    common = [n for n in _script_zip.namelist()
              if n.startswith("script/") and not n.split("/")[-1].startswith("c")]
    print(f"[脚本包] 非卡牌脚本（公共库）共 {len(common)} 个，样例: "
          f"{[c.split('/')[-1] for c in common[:8]]}")

    test_name = b"constant.lua"
    n = (ctypes.c_int * 1)(0)
    ptr = _script_reader(test_name, n)
    print(f"[自测] {test_name.decode()} -> 指针={'非空' if ptr else 'NULL'} 长度={n[0]} 字节")

    # ---- 现在再试着创建对局 ----
    core.create_duel_v2.restype = ctypes.c_void_p
    core.create_duel_v2.argtypes = [ctypes.c_uint64]
    print("\n[create_duel_v2] 调用中 ...")
    duel = core.create_duel_v2(ctypes.c_uint64(1))
    print(f"[create_duel_v2] handle = {duel}")

    if not duel:
        print("\n[失败] 仍返回空指针")
        return 1

    core.set_player_info.restype = None
    core.set_player_info.argtypes = [ctypes.c_void_p, ctypes.c_int,
                                     ctypes.c_int, ctypes.c_int, ctypes.c_int]
    core.set_player_info(duel, 0, 8000, 5, 1)
    core.set_player_info(duel, 1, 8000, 5, 1)
    print("[set_player_info] OK")

    core.end_duel.restype = None
    core.end_duel.argtypes = [ctypes.c_void_p]
    core.end_duel(duel)
    print("[end_duel] OK")

    print(f"\n回调命中统计: {_hits}")
    if _missing_scripts:
        print(f"缺失脚本样例: {sorted(_missing_scripts)[:5]}")
    print("\n>>> P0-2 通过：legacy 回调路径可用，无需构造 OCG_DuelOptions。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
