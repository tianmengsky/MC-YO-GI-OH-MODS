# AI 对局空引用修复（0.6.33，2026-10-18）

## 结论

本次截图中的错误来自服务端独立决斗宿主内的 Windbot，根因是 AI 初始化时双方卡组张数错误，不是网络延迟或客户端内存溢出。已修复宿主、构建并校验 0.6.33 整合模组，部署到手机和用户明确授权的 Tianmeng 发行客户端。原游戏目录 `F:\Minecarft\MC游戏王1.20.1` 未修改；没有复制整套客户端。

## 证据与全链路

1. 手机生产日志记录 16:34:58 和 16:37:21 UTC 的两次失败，客户端对应 2026-10-18 00:34:59 和 00:37:22。两次最后移动消息均为 `3235D09003004004080004000400080000`。
2. `ReferenceHost` 的 `sessions/*/host.jsonl` 保存完整 .NET 堆栈：`WindBot.Game.Duel.AddCard → GameBehavior.OnMove → GameClient.Feed → WindBotAi.FeedMessage`，后续还出现 `OnSpSummoned` 和 query feed 空引用。这些是同一状态缺损的后果。
3. 该消息为编号 59822133（苍眼银龙）从玩家 0 的额外卡组序号 4 移动到怪兽区。真实双方卡组为主卡组 40/40、额外 15/0。
4. 原宿主 `WindBotAi.FeedGameStart` 从 AI 自己牌组取得 `main/extra`，重复写入双方 MSG_START 张数，导致 Windbot 对玩家额外卡组建成 0 个占位对象。查询更新只更新现有对象，不能补齐这个列表，因此从额外移动时取到 null。
5. 同一原始初始化还写入规则字节 5，而实际 `Native.StartDuel` 已改为 MR3。即使双方额外张数碰巧相同，AI 对规则的认识仍不一致。
6. 两份真实消息记录在旧张数下均复现相同空引用；正确张数下分别重放 270 / 1260 条消息通过。重放验证消息消费者的状态一致性，不代表重新生成了相同随机决策；真实内核对局另行测试。

## 修复

- `PatchHost.cs` 对 SHA 固定的原始程序集作检查后修改：关闭错误的伪 MSG_START，在 DuelCore 已添加双方牌组、创建 AI 后调用初始化钩子。
- `Adapter.cs` 用 `query_field_count` 从原生内核读取两个座位的主卡组与额外张数，发送一次 MR3 MSG_START。只建立未知卡占位，不暴露对方隐藏牌身份。AI 座位、自己的 seeded 数量与双方张数分别处理。
- Windows 和 Linux 使用同一修复，再保留 Linux ConfirmCards 分帧适配。没有在 AddCard/OnMove 中吞掉异常或凭空补卡。
- Java 错误继续取消失败对局、不计胜负，并把宿主完整 stack 附到异常原因；warn 不再一律误称“无法分帧”。当前未重启的生产 Java 仍用旧的诊断代码，下次服务端正常启动会加载新代码。
- 原三个嵌套模组、旧注册名、卡牌、卡包及存档身份保持不变。新旧整合包逐条目比较仅改变版本清单、内嵌 verifier、两平台宿主资源和 ReferenceHost 类；没有条目增加或删除。

## 验证

| 验证 | 结果与证据 |
| --- | --- |
| 真实失败基线与修复重放 | 两场基线在同一移动上 NullReference；修复处理 270 / 1260 条消息，见 `generated/ai-feed-repair/smoke-build/` |
| 非对称张数与规则 | 40/60 主卡组、15/0 额外；AI 两个座位；MR3 flags；初始隐藏卡 ID 为 0；捕获的特殊召唤成功 |
| Windows 真实宿主 | Maxx C 发动/取消、三眼怪必发、额外非第一候选和重复副本、带 AI 的额外召唤双座位、并发隔离、重试恢复；完整 13 回合对局通过 |
| 手机 ARM64 真实宿主 | 上述回归全部通过；完整对局 13、6、4 回合，后两场使用保存的出错双方牌组并交换 AI 座位 |
| 正式 0.6.33 整合包 | 手机从该包实际解出宿主再运行全部回归；同一出错牌组 seed 41 完成 22 回合、155 次玩家提示、13 次召唤、14 次攻击、13 次伤害 |
| Forge 集成 | 16 项 required GameTest 全部通过，见 `forge-build-tests.log` |
| 内容校验 | 打包逐条目校验、嵌套 jar 哈希校验、发行客户端和手机最终 jar SHA256 相同 |

## 部署与使用

- 正式模组：`addon/build/libs/yugioh-mdpro-integrated-0.6.33-alpha.jar`。
- SHA256：`6662d14a52316b1d6f59235937fd40f804e40ffbe00ce23aad0437d9b97440ee`。
- 手机：已安装 `~/mcserver/mods/yugioh-mdpro-integrated-0.6.33-alpha.jar`。确认无生产决斗宿主运行后，替换生产 runtime 的 `adapter.dll` / `duel_host.dll`；每场决斗启动新进程，因此随后新发起的 AI 对局立即获得修复。Minecraft 进程 14898 未重启，在线连接未主动断开。
- 手机旧模组和旧 DLL：`~/mdpro-workspace/generated/deployment-backup/ai-state-0.6.33/`。下次正常启动服务端会加载完整 0.6.33。
- 客户端：原地替换 `F:\Minecarft\MC游戏王Tianmeng版\.minecraft\versions\1.20.1-游戏王整合包Tianmeng版\mods` 内的整合模组。备份位于 `generated/deployment-backup/tianmeng-ai-state-0.6.33/`；Java 17 和启动内存配置保留。运行中的客户端下次启动才加载新模组；联机 AI 决策已经由手机修复宿主执行。
- 差异传输只传约 76 MiB，并在手机用流式重建完整 jar 后验证精确 SHA256。没有完整复制发行客户端。
- 新发行压缩包：`generated/MC游戏王Tianmeng版-0.6.33-AI修复.7z`，1,706,545,241 字节，SHA256 `b3a4cb9d69dc7ec20a791e39ba7af55b7cc65783a9474b27951665e010f587e6`；`7z t` 通过。直接从更新后的发行目录归档，排除账号缓存、存档、服务器地址及启动令牌相关文件。

## 仍需区分的问题与验证边界

- 16:23:23 UTC 的普通连接断线带有 `CompoundTag ... nbt is null`，早于本次决斗。已检索两端日志和附属模组网络代码，现有记录没有具体解码器堆栈，不能可靠归属到某一模组。它与本次 .NET AI 堆栈独立，不能声称此次已修复。
- 未发现此次错误伴随 OutOfMemory；没有根据本次截图改变客户端堆内存。既有启动修复保留。
- 目前覆盖真实出错消息、双平台、非对称牌组、召唤与完整对局，不等同于穷举历史卡池全部卡牌效果。客户端手动复验新一局仍有价值。
