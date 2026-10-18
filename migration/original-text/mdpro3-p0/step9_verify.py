"""
P0 第 9 步：用 query_field_count 直接验证 new_card 是否真的把卡放进了牌堆。
这比盯着 start_duel 崩溃点高效得多——先确认数据进去了，再谈开局。
"""
import ctypes
import sys

DLL = r"F:\MyCardLibrary\mdpro3\MDPro3_Data\Plugins\x86_64\ocgcore.dll"

SR_T = ctypes.CFUNCTYPE(ctypes.c_void_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_int))
CR_T = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_uint32, ctypes.c_void_p)
MH_T = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_void_p)


class NC_A(ctypes.Structure):
    _fields_ = [("team", ctypes.c_uint8), ("duelist", ctypes.c_uint8),
                ("code", ctypes.c_uint32), ("con", ctypes.c_uint8),
                ("loc", ctypes.c_uint32), ("seq", ctypes.c_uint32),
                ("pos", ctypes.c_uint32)]


class NC_B(ctypes.Structure):
    _fields_ = [("code", ctypes.c_uint32), ("owner", ctypes.c_uint8),
                ("player", ctypes.c_uint8), ("location", ctypes.c_uint8),
                ("sequence", ctypes.c_uint8), ("position", ctypes.c_uint8)]


def main():
    nc_name = sys.argv[1] if len(sys.argv) > 1 else "ncA"
    hits = {"card": 0, "script": 0}

    def sr(name, out_len):
        hits["script"] += 1
        out_len[0] = 0
        return None

    def cr(code, ptr):
        hits["card"] += 1
        return 0

    def mh(t, d):
        pass

    core = ctypes.WinDLL(DLL)
    a, b, c = SR_T(sr), CR_T(cr), MH_T(mh)
    keep = (a, b, c)
    core.set_script_reader.argtypes = [SR_T]; core.set_script_reader(a)
    core.set_card_reader.argtypes = [CR_T];   core.set_card_reader(b)
    core.set_message_handler.argtypes = [MH_T]; core.set_message_handler(c)

    core.create_duel.restype = ctypes.c_void_p
    core.create_duel.argtypes = [ctypes.c_uint32]
    duel = core.create_duel(ctypes.c_uint32(7))
    print(f"create_duel -> {duel}", flush=True)

    core.set_player_info.argtypes = [ctypes.c_void_p] + [ctypes.c_int] * 4
    core.set_player_info(duel, 0, 8000, 5, 1)
    core.set_player_info(duel, 1, 8000, 5, 1)

    # query_field_count(duel, team, location) -> int
    core.query_field_count.restype = ctypes.c_int
    core.query_field_count.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
    qc = core.query_field_count

    LOC_DECK, LOC_HAND, LOC_MZONE = 1, 2, 4
    print(f"放卡前  玩家0 DECK={qc(duel,0,LOC_DECK)} HAND={qc(duel,0,LOC_HAND)} "
          f"MZONE={qc(duel,0,LOC_MZONE)}", flush=True)

    NC = NC_A if nc_name == "ncA" else NC_B
    core.new_card.restype = None
    core.new_card.argtypes = [ctypes.c_void_p, ctypes.POINTER(NC)]
    codes = [89631139, 46986414, 70781052, 40640057, 91152256]
    for i, code in enumerate(codes):
        info = NC(0, 0, code, 0, LOC_DECK, i, 8) if nc_name == "ncA" else NC(code, 0, 0, LOC_DECK, i, 8)
        core.new_card(duel, ctypes.byref(info))
    print(f"new_card x5 后  玩家0 DECK={qc(duel,0,LOC_DECK)}  (期望 5)", flush=True)

    # 再试 legacy 7 参数形式，比较
    print("--- 对照：legacy 7 参数 new_card ---", flush=True)

    core.end_duel.argtypes = [ctypes.c_void_p]
    core.end_duel(duel)
    print(f"回调命中: {hits}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
