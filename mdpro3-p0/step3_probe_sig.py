"""
P0 第 3 步：探测 create_duel* 的真实签名。

现象：create_duel_v2(0x123456789ABCDEF0) 崩在读 0xFFFFFFFFFFFFFFFF；
      create_duel_v2(1)                  崩在读 0x1。
      => 第一个参数很可能被当成指针，而不是按值传递的 seed。

策略：每个假设在独立子进程里跑，崩溃只影响该子进程，驱动脚本汇总结果。
"""
import ctypes
import sys
import os

DLL = r"F:\Minecarft\游戏王mod工作区\inputs\mdpro3\MDPro3_Data\Plugins\x86_64\ocgcore.dll"


def load():
    core = ctypes.WinDLL(DLL)
    # 回调全部注册为"安全的空实现"，排除回调缺失造成的干扰
    SR = ctypes.CFUNCTYPE(ctypes.c_void_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_int))
    CR = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_uint32, ctypes.c_void_p)
    MH = ctypes.CFUNCTYPE(None, ctypes.c_int, ctypes.c_void_p)

    def sr(name, out_len):
        out_len[0] = 0
        return None

    def cr(code, data):
        return 0

    def mh(t, d):
        pass

    keep = (SR(sr), CR(cr), MH(mh))
    core.set_script_reader.argtypes = [SR]
    core.set_card_reader.argtypes = [CR]
    core.set_message_handler.argtypes = [MH]
    core.set_script_reader(keep[0])
    core.set_card_reader(keep[1])
    core.set_message_handler(keep[2])
    return core, keep


def probe(mode):
    core, keep = load()

    if mode == "v1_val":
        core.create_duel.restype = ctypes.c_void_p
        core.create_duel.argtypes = [ctypes.c_uint32]
        h = core.create_duel(ctypes.c_uint32(1))

    elif mode == "v2_val":
        core.create_duel_v2.restype = ctypes.c_void_p
        core.create_duel_v2.argtypes = [ctypes.c_uint64]
        h = core.create_duel_v2(ctypes.c_uint64(1))

    elif mode == "v2_ptr":
        core.create_duel_v2.restype = ctypes.c_void_p
        core.create_duel_v2.argtypes = [ctypes.POINTER(ctypes.c_uint64)]
        seed = (ctypes.c_uint64 * 4)(1, 2, 3, 4)
        h = core.create_duel_v2(seed)

    elif mode == "v2_ptr1":
        core.create_duel_v2.restype = ctypes.c_void_p
        core.create_duel_v2.argtypes = [ctypes.POINTER(ctypes.c_uint64)]
        seed = (ctypes.c_uint64 * 1)(1)
        h = core.create_duel_v2(seed)

    elif mode == "v2_ex_null":
        core.create_duel_v2_ex.restype = ctypes.c_void_p
        core.create_duel_v2_ex.argtypes = [ctypes.c_uint64, ctypes.c_void_p]
        h = core.create_duel_v2_ex(ctypes.c_uint64(1), None)

    elif mode == "v2_ex_zero":
        core.create_duel_v2_ex.restype = ctypes.c_void_p
        core.create_duel_v2_ex.argtypes = [ctypes.c_uint64, ctypes.c_void_p]
        buf = ctypes.create_string_buffer(1024)      # 全 0 的 options
        h = core.create_duel_v2_ex(ctypes.c_uint64(1), ctypes.cast(buf, ctypes.c_void_p))

    elif mode == "v1_ex_zero":
        core.create_duel_ex.restype = ctypes.c_void_p
        core.create_duel_ex.argtypes = [ctypes.c_uint32, ctypes.c_void_p]
        buf = ctypes.create_string_buffer(1024)
        h = core.create_duel_ex(ctypes.c_uint32(1), ctypes.cast(buf, ctypes.c_void_p))

    elif mode == "v1_ex_null":
        core.create_duel_ex.restype = ctypes.c_void_p
        core.create_duel_ex.argtypes = [ctypes.c_uint32, ctypes.c_void_p]
        h = core.create_duel_ex(ctypes.c_uint32(1), None)

    else:
        print(f"RESULT {mode} UNKNOWN_MODE")
        return 2

    print(f"RESULT {mode} OK handle={h}")
    if h:
        core.end_duel.argtypes = [ctypes.c_void_p]
        core.end_duel(h)
    return 0


if __name__ == "__main__":
    raise SystemExit(probe(sys.argv[1]))
