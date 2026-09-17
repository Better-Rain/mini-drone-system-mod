# 交接：多机适配 + 动捕源切换（新会话从这里开始）

本文是 2026-09-17 那次长会话的交接。工作横跨两个仓库：

- 模组：`C:\Users\VLT_BR\Projects\mini-drone-system-mod`（Fabric 1.21.1，Java 21）
- 主项目：`C:\Users\VLT_BR\Projects\mini-drone-system`（C++ 后端 + Electron 前端）

## 0. 一句话现状

多机适配的**模组侧基础设施已完成并推送**（机队注册表、管理器接线、信标可携带机队、实体携带 drone id，155 项单测全绿）；
**动捕源热切换有两个已定位的缺陷**（切换不改变飞机实际吃的动捕源；切换后旧源的无人机不消失），修复方案见 §2，
实现主要在主项目（后端 `apply_mocap_source` + 前端按源回收无人机）。

## 1. 已提交（都已 push）

### 模组仓库（`git@github.com:Better-Rain/mini-drone-system-mod.git`，分支 `main`）

| 提交 | 内容 |
| --- | --- |
| `4925d48` | `VirtualDroneFleet`：按 drone id 索引的机队；id（`minecraft_drone_01`/`_02`…）与 MAVLink sysid 的发放规则；**移除后不复用 sysid**（后端按 sysid 发现飞机）；保持创建顺序；上限 8 架 |
| `624351e` | `VirtualDroneManager` 改为通过机队创建飞机，暴露 `fleet()` / `droneById(String)`；单机身份与行为不变（现场验证） |
| `ca3ec65` | `MocapHealthFleet.appendFleetDrones(...)`：健康信标可携带 `drones` 数组（每机 id/sysid/位姿/armed/**按机 safety_latched**）；**只有一架时载荷逐字节不变**（单测逐字节断言）；已在 `MavlinkTransport` 的信标发送处接线 |
| `11b1ace` | `DroneEntity` 携带同步的 drone id（`droneId()` / `setDroneId(String)`，默认 `minecraft_drone_01`） |

单测 **155 项全绿**；`gradlew test --offline` 需要 `JAVA_HOME=C:\Users\VLT_BR\AppData\Roaming\.minecraft\runtime\java-runtime-delta` 与 `--offline`。

### 主项目仓库（分支 `codex/mavlink-official-c`）

| 提交 | 内容 |
| --- | --- |
| `c10a201` / `7ea225b` / `d379acc` | 文档：世界尺度的模组侧说明；"真实动捕搜不到"的排查（**relay 不在 `dev:all` 里**）；源切换语义（显式切换，不做自动优先）；**后端日志实际位置**与它答不了的两个问题 |

## 2. ⚠️ 待修的两个缺陷（已定位，有证据）

### (a) 切换动捕源不改变飞机实际吃的动捕源

现象（本次实测）：

```text
A) 切换前  : effective_source = {mode: real, health: 127.0.0.1:15151, id: 54}   飞机: real_drone_001_001_ip54
B) 切 virtual: 命令返回 source_connected，但 effective_source 一字未变
   飞机列表: real_drone_001_001_ip54（423 帧，从不中断）
```

后端自己的日志（`logs/field-backend-*.out.log` 或 `%APPDATA%\mini_drone_system\backend.log`）：

```text
[adapter:mavlink.mocap_source.switched] Rebound the shared motion-capture relay health listener to the selected source
[adapter:mavlink.mocap_health.state_changed] Motion-capture forwarding is healthy
```

**结论**：切换**只重绑了"共享的 relay 健康监听器"**，没有切换 **MAVLink 侧的动捕 profile**
（启动参数把它钉在 `--mavlink-mocap-mode=real --mavlink-mocap-profile=real_mocap`）。

**修复方向**：在 `apply_mocap_source`（主项目后端源码，搜 `apply_mocap_source` / `mocap_source.switched`）里
把 `--mavlink-mocap-*` 那一组随选定源一起切换，并在切换时**解绑属于旧源的 MAVLink peer**。
注意：早先该切换**是生效的**（虚拟源在场、真实源缺席时），说明它与"真实源是否在线"耦合 —— 修的时候要覆盖两种情况。
后端源码只用 ASCII。

### (b) 切换后旧源的无人机不消失（操作者报告：两边都显示）

现象：切到虚拟源后，**虚拟与现实中的无人机同时显示**。

**修复方向**：
1. **后端**：切换源时解绑/丢弃旧源的飞机（见 (a) 的解绑）；
2. **前端**：把"飞机存在性"与**当前动捕源**绑定 —— 源切换后旧源对象**立即移出列表**，而不是等超时
   （前端已有 `drone-presence` 机制与对应测试 `tests/drone-presence.test.mjs`，从这里改最自然）。

**验收标准**：切虚拟 → 界面只剩 `minecraft_drone_01`；切真实 → 只剩真机；两个方向都要实测。

## 3. 多机适配剩余步骤

| 步骤 | 说明 / 指针 |
| --- | --- |
| 世界控制器按 id 索引实体 | `src/main/java/com/vltbr/minidrone/world/DroneWorldController.java` 目前是单个 `private DroneEntity entity;` 字段（约第 28 行）；需改成按 `droneId` 索引，生成与写回都按 id 走。实体已有 `droneId()`/`setDroneId()` 可用 |
| 放置/收回按机绑定 | `DronePlacementHook` / `DronePlacementItem` / `DroneEntity.interact`：右键方块＝新增一架（操作者尚未确认这个交互，见 §6）、右键某架＝收回**那一架**（现在收回的是 `primaryDrone`） |
| 每机链路标识 | 模组 `MavlinkTransport` 目前一条链路 + 一个 `expected_drone_id`（默认 `minecraft_drone_01`）；`VirtualDroneFleet` 已保证每机 sysid 唯一 |
| 后端接受多个 `expected_drone_id` | 后端在 MAVLink 侧**已支持多机**（见主项目 `docs/ardupilot-sitl-multi-vehicle-runbook-zh.md`：`auto_bind` 按 sysid/compid 发现多架），缺的是**虚拟动捕源**这一层（现在是"一源一机"：`--mocap-expected-drone-id` 单个 + 按它过滤信标，见 `docs/minecraft-virtual-mocap-onboarding.md`） |
| 两架同时飞实测 | 两架同时悬停/点到点；其中一架撞机不影响另一架（信标里 `safety_latched` 已按机区分） |

## 4. 现场操作手册（这次踩过的坑都在这）

- **启动**：`npm run dev:real`（后端 + relay + 前端一条命令）或分开跑 `npm run mocap:relay` + `npm run dev:all`。
  ⚠️ **`dev:all` 不含 relay** —— 少了它就"真实动捕搜不到、只能连无人机"。
- **端口速查**：真实源 `15150/15151/15152`（relay 在跑时 15152 有人监听）；虚拟源（Minecraft）`18151/18152`。
- **后端日志**：supervisor 写的在 `%APPDATA%\mini_drone_system\backend.log`；自己起后端时用
  `Start-Process ... -RedirectStandardOutput logs\field-backend-<stamp>.out.log`。
  ⚠️ 仓库 `logs/` 里默认**没有**后端日志（只有手动重启重定向才会产生）。
- **装 mod jar**：**必须先停客户端**，复制后**校验可读性**，再启动。会话中因为在游戏运行时覆盖 jar，
  曾把一个线程写死（`ZipException: invalid LOC header`），表现为"命令随机报错"。
- **改代码**：只用**精确锚点编辑**（读原文→整段替换）。会话中三次"按行插入/删除"把 Java 文件写坏
  （重复字段、丢大括号），其中一次差点留下不可编译状态。
- **顺序**：任何改动都要**先验证再安装**。会话里有一次先装 jar 后验证，导致无法判断现场故障是否由改动引入。
- **不要**在操作者可能飞真机时重启后端 —— 真机 MAVLink 链路走在它上面（本次已获得明确授权才重启）。

## 5. 现场验证工具

```powershell
# 1) 模组侧是否在广播虚拟源（证明"不是模组没发"）
#    UDP 发 VLT_RELAY_STATUS_V1 到 127.0.0.1:18152
#    → {"message":"virtual motion-capture source is online","source_packet_age_ms":0}

# 2) 后端侧切换 + 观测（改 mode 与端口即可双向切）
#    WS ws://127.0.0.1:8080 发 connect_mocap_source
#    virtual: mode=virtual, profile=minecraft_virtual_mocap, health=127.0.0.1:18151,
#             control=127.0.0.1:18152, expected_drone_id=minecraft_drone_01
#    real   : mode=real,    profile=real_mocap,             health=127.0.0.1:15151,
#             control=127.0.0.1:15152, expected_drone_id=''

# 3) 飞机列表来自 telemetry_frame 的 payload.drones[].drone_id
# 4) 飞行验收清单（悬停/阶跃/顶速/擦碰/硬撞，缺障碍物时如实报 SKIP）
node scripts/course-check.mjs --north        # 在模组仓库
```

## 6. 待操作者确认的一件事

第二架无人机**怎么出现**：我按"「无人机放置器」右键方块 = 在该处新增一架并分配 id"实现（尚未确认）。
备选是新增一个专用"生成器"物品。开工前问一句即可。

## 7. 未决/未证实（不要当成结论）

- 后端日志里 `mavlink.peer.rejected_packet`（非法 MAVLink 帧）的来源：15:15:47–15:18:56 有一段洪流，
  与 `link.timeout`(15:18:03) / `link.restored`(15:18:28) 前后相邻；**日志不记发送方 IP**，未定位。
- 前端无人机列表里一度出现第二个真实身份 `real_drone_001_001_ip1`（其后消失）。**未查证**是否真实第二台设备，
  也可能是过期绑定被 `--mavlink-peer-timeout-ms=10000` 回收。
