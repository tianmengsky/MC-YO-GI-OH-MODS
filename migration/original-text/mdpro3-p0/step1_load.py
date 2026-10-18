"""
P0 第 1 步：验证 ocgcore.dll 能被加载，并确认导出函数签名可用。

目的：在写任何 Java 之前，先证明内核可以在本进程内被驱动。
"""
import ctypes
import os
import sys
import struct

DLL = r"F:\MyCardLibrary\mdpro3\MDPro3_Data\Plugins\x86_64\ocgcore.dll"
DB = r"F:\MyCardLibrary\mdpro3\Data\locales\zh-CN\cards.cdb"
SCRIPTS = r"F:\MyCardLibrary\mdpro3\Data\script.zip"


def main():
    print("=" * 70)
    print("P0-1  ocgcore.dll 加载与 API 探测")
    print("=" * 70)
    print(f"Python  : {sys.version.split()[0]}  ({struct.calcsize('P') * 8} bit)")
    print(f"DLL     : {DLL}")
    print(f"存在    : {os.path.exists(DLL)}")

    if struct.calcsize("P") != 8:
        print("\n[失败] 必须使用 64 位 Python 才能加载 x86_64 的 ocgcore.dll")
        return 1

    core = ctypes.WinDLL(DLL)
    print(f"加载    : 成功  (句柄 {core._handle:#x})\n")

    # ---- 1. 确认全部 24 个宿主 API 都可解析（来自 PE 导出表，非字符串猜测）----
    host_api = [
        "create_duel", "create_duel_v2", "create_duel_ex", "create_duel_v2_ex",
        "start_duel", "end_duel", "process", "get_message", "get_log_message",
        "set_responsei", "set_responseb", "set_player_info", "new_card", "new_tag_card",
        "set_script_reader", "set_card_reader", "set_message_handler",
        "default_script_reader", "preload_script",
        "query_card", "query_field_card", "query_field_count", "query_field_info",
        "load_registry",
    ]
    debug_api = [
        "clear_registry", "set_registry_value", "get_registry_value",
        "get_registry_keys", "dump_registry",
        "clear_all_lua_coverages", "clear_lua_coverage",
        "dump_all_lua_coverages", "dump_lua_coverage",
        "get_all_lua_coverages_dump_size", "get_lua_coverage_dump_size",
    ]
    missing = [n for n in host_api + debug_api
               if not hasattr(core, n)]
    print(f"[符号] 导出函数 {len(host_api + debug_api) - len(missing)}/{len(host_api + debug_api)} 可解析"
          + (f"  缺失: {missing}" if missing else "  (24 宿主 + 11 调试 = 35，与 PE 导出表一致)"))
    if missing:
        return 1

    # ---- 2. create_duel_v2：最简形态，只吃一个 64 位种子 ----
    core.create_duel_v2.restype = ctypes.c_void_p
    core.create_duel_v2.argtypes = [ctypes.c_uint64]
    duel = core.create_duel_v2(ctypes.c_uint64(0x1234_5678_9ABC_DEF0))
    print(f"[create_duel_v2] handle = {duel}")

    if not duel:
        print("\n[失败] create_duel_v2 返回空指针")
        return 1

    # ---- 4. 试着把两边的玩家信息塞进去 ----
    core.set_player_info.restype = None
    core.set_player_info.argtypes = [ctypes.c_void_p, ctypes.c_int,
                                     ctypes.c_int, ctypes.c_int, ctypes.c_int]
    core.set_player_info(duel, 0, 8000, 5, 1)
    core.set_player_info(duel, 1, 8000, 5, 1)
    print("[set_player_info] 双方 LP=8000 起手5 每回合抽1  → 调用成功")

    # ---- 5. 收尾 ----
    core.end_duel.restype = None
    core.end_duel.argtypes = [ctypes.c_void_p]
    core.end_duel(duel)
    print("[end_duel] 已释放对局")

    print("\n>>> P0-1 通过：内核可在本进程加载并创建/销毁对局。")
    print("    下一步 = 注册 script_reader / card_reader 回调，真正跑起一场决斗。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
