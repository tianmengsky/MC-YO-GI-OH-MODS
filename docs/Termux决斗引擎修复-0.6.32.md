# Termux 决斗引擎修复（0.6.32-alpha，2026-10-17）

## 已证实的问题

手机为 PFVM10 / Linux aarch64 / Termux Java 17.0.20，Forge 1.20.1-47.4.28。存在两层问题：

1. 未移植的 0.6.31 在手机执行 Windows `adapter.exe`，服务端日志报 `error=13, Permission denied`。这不是简单 chmod 能修复的 Windows x64/Android ARM64 架构差异。
2. 已移植的宿主能开局，但不能通过真实效果回归。手机 `ReferenceHostSmoke` 在三眼怪被黑洞破坏、检索并向对手确认卡片后报：`Unknown or truncated core message`，function=31，length=10。Java 据此取消对局。

失败包原字节：`1F010180DF6E05000203`。手机 ARM64 内核源码的 `MSG_CONFIRM_CARDS` 为 `消息号 + 玩家 + 数量 + 7字节卡片*n`，即 `3 + 7*n`。示例宿主按另一种布局额外跳过一字节，把卡片密码低字节当数量，导致截断或吞入同批下一条消息。

## 修复范围

- `addon/duel-host-adapter/PatchLinuxHost.cs` 从已审核的 Windows 宿主生成独立 Linux 版本：AnyCPU、移除 Windows 专用 `SetDllDirectory` 初始化、只对消息 31 替换分帧长度。
- `Adapter.cs` 的 Linux hook 严格检查数量和剩余长度；其它消息继续经过原解析器，不忽略未知消息警告。
- `stage_reference_host_linux.py` 重编 adapter 和补丁，生成依赖描述与逐文件 SHA-256 清单，验证原生库为 ELF64 AArch64。无需 kernel32 空实现桩。
- Windows `reference-host.zip` 字节不变；集成包相对当前移植版 0.6.31 只改变发行版本元数据和 Linux 资源包。网络协议仍为 8，旧注册身份、卡片、卡包与存档格式均未改动。
- Termux 启动脚本继续使用 proot + 已有 `~/ubuntu/opt/dotnet`，检查依赖并限制 .NET GC 堆至 192 MiB。Java/Forge 保持 Termux 原生运行。

## 验证记录

- `generated/termux-repair/baseline-smoke.log`：修复前手机确定性失败，function=31。
- `baseline-sessions/`：失败宿主 JSONL 和卡组；`server-original-error.log.gz`：原 Windows 启动错误。
- `protocol-build.log`：实际补丁程序集解析失败包、多卡、批次拼接、截断和原解析回退均通过。
- `phone-regressions.log`：手机真实 Java→proot/.NET→ARM64 ocgcore→Lua→Java 请求/应答回归通过；包括增殖的 G 取消/发动、三眼怪强制效果及向 AI 展示检索卡、非首额外候选及素材、并发关闭隔离、Windbot 座位 0/1、非法应答恢复。
- 同日志记录一局完整 MR3 决斗：13 回合、37 次玩家应答、13 次召唤、12 次攻击、6 次伤害，内核 winner=0。
- `windows-regression-build.log`：Windows 同组效果及完整对局回归通过。
- `phone-actual-deck.log`：使用手机实际保存的玩家/女仆卡组，在手机 ARM64 上自动完成 9 回合、61 次应答、5 次召唤、5 次攻击、4 次伤害，内核 winner=0。
- `windows-forge-gametests-isolated.log`：All 16 required tests passed；手机生产 Forge 47.4.28 在隔离世界启动并执行 `mdpro verify`，六回合验证通过。正式 Forge 安装不提供 userdev 的 GameTest launch handler，因此手机使用实际 forgeserver 入口验证。
- `release-diff.json`：发行资源差异与 SHA；`linux-package-audit.json`：Linux 资源逐文件哈希。

手机隔离目录是 `~/mdpro-workspace/run-server/`，与正式 `~/mcserver/world` 分离。Windows 构建、分析与日志均在本工作区；`inputs/` 与原游戏目录未改动。

## 运行与回退

发行文件：`addon/build/libs/yugioh-mdpro-integrated-0.6.32-alpha.jar`。SHA-256：`411f5dd998980a31240d22b5dd3580dc42277060bbd8c0b95a9bc9b75a550658`。

已安装手机 `~/mcserver/mods/yugioh-mdpro-integrated-0.6.32-alpha.jar`。原手机 0.6.31（SHA-256 `313101b08570f98fe2a85143abf90ed949953e17dd3ebe824135a1aa7caf6279`）备份在 `~/mdpro-workspace/generated/deployment-backup/termux-0.6.32/`，并复制回本工作区 `generated/deployment-backup/termux-0.6.32/phone-yugioh-mdpro-integrated-0.6.31-alpha.jar`。没有改动手机的配置、卡组或世界存档内容；启动 Minecraft 自身会照常读写存档。电脑游戏实例尚未更换 JAR，网络协议仍为 8。

正式服务端已启动，实际安装路径中执行 `mdpro verify` 返回“示例决斗宿主验证通过：MR3、六回合、弃牌与独立进程生命周期”。最终证据为 `generated/termux-repair/phone-production-final.log`。与手机旧 JAR 比较也只有发行版本元数据、Linux 资源包发生变化，见 `phone-release-diff.json`。隔离测试服已正常停止；真实双客户端/女仆界面操作仍待玩家进服实测。

运行环境需保留手机上的 proot、Ubuntu ARM64 rootfs、.NET 8 ARM64 运行时。不能把 Windows exe/DLL 直接用于手机。模组在首次验证/决斗时按 Linux 资源清单安装 `mdpro-reference/`；手改该目录的受管文件会被清单恢复，修复必须进入 JAR。

服务端 OP/控制台可执行 `/mdpro verify` 检查 MR3 六回合流程。该命令本身不覆盖复杂卡片效果；本次同时运行上述效果及完整对局回归。真实双客户端操作、全部 8,231 张卡片组合仍需持续实战验证。

回退应先正常停服，再把备份的旧 JAR 放回 mods 并移出新 JAR；不要两版并装，不需要回滚或重写世界存档。
