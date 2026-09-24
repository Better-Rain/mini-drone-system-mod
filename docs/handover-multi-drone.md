# 交接：多机适配 + 动捕源切换（新会话从这里开始）

本文是 2026-09-17 那次长会话的交接。工作横跨两个仓库：

- 模组：`C:\Users\VLT_BR\Projects\mini-drone-system-mod`（Fabric 1.21.1，Java 21）
- 主项目：`C:\Users\VLT_BR\Projects\mini-drone-system`（C++ 后端 + Electron 前端）

## 0. 一句话现状

多机适配**端到端跑通了**：模组侧基础设施（机队注册表、管理器接线、信标携带机队、实体携带 drone id）+
后端逐机发现/逐机准入/逐机参数与姿态状态 + 前端逐机元数据与在场过滤，**三架同时飞、各飞各的航点已现场验收通过**（§3.16）。
动捕源热切换的两个缺陷已修复并双向验收（§2）；世界侧按 drone id 索引实体，放置＝新增一架（§3.5）；
飞机随世界存盘（§3.8）；第二架能收指令（§3.6、§3.9）；**碰撞＝普通物理，不损坏不锁存**（§3.15）。
仅剩三项小尾巴，见 §3.7。

## 1. 已提交（都已 push）

### 模组仓库（`git@github.com:Better-Rain/mini-drone-system-mod.git`，分支 `main`）

| 提交 | 内容 |
| --- | --- |
| `4925d48` | `VirtualDroneFleet`：按 drone id 索引的机队；id（`minecraft_drone_01`/`_02`…）与 MAVLink sysid 的发放规则；**移除后不复用 sysid**（后端按 sysid 发现飞机）；保持创建顺序；上限 8 架 |
| `624351e` | `VirtualDroneManager` 改为通过机队创建飞机，暴露 `fleet()` / `droneById(String)`；单机身份与行为不变（现场验证） |
| `ca3ec65` | `MocapHealthFleet.appendFleetDrones(...)`：健康信标可携带 `drones` 数组（每机 id/sysid/位姿/armed/**按机 safety_latched**）；**只有一架时载荷逐字节不变**（单测逐字节断言）；已在 `MavlinkTransport` 的信标发送处接线 |
| `11b1ace` | `DroneEntity` 携带同步的 drone id（`droneId()` / `setDroneId(String)`，默认 `minecraft_drone_01`） |

单测 **162 项全绿**；`gradlew test --offline` 需要 `JAVA_HOME=C:\Users\VLT_BR\AppData\Roaming\.minecraft\runtime\java-runtime-delta` 与 `--offline`。

### 主项目仓库（分支 `codex/mavlink-official-c`）

| 提交 | 内容 |
| --- | --- |
| `c10a201` / `7ea225b` / `d379acc` | 文档：世界尺度的模组侧说明；"真实动捕搜不到"的排查（**relay 不在 `dev:all` 里**）；源切换语义（显式切换，不做自动优先）；**后端日志实际位置**与它答不了的两个问题 |
| 本轮（未提交） | §2 的两个缺陷修复 + 前端按源回收；见下 |

## 2. ✅ 两个动捕切换缺陷（已修复 + 现场双向验收）

### (a) 切换动捕源不改"飞机实际吃的源" —— 已修

**实际根因（比原判断多一层）**：适配器里的 `--mavlink-mocap-*` 副本**确实**跟着 `apply_mocap_source`
一起切了（`effective_source` 会变），真正没跟的是**"哪条链路属于当前源"**：

- 切到虚拟源后，真机那条 `udpout://10.1.1.54:14550` 仍然**保持绑定**、继续发布 `real_drone_001_001_ip54`，
  而它的动捕门禁已经被换成虚拟源的 `expected_drone_id=minecraft_drone_01` ——
  即"真机被虚拟源的判据门禁着"，这正是操作者看到的"飞机吃的还是旧源"。

**修复**：切换时按"源声明的飞机链路"决定归属，不属于新源的链路**当场解绑**：

- `MocapSourceSwitch.h`：新增纯策略 `mocap_source_owns_aircraft_link(source_aircraft_endpoint, link_endpoint_url)`
  （源不声明飞机链路 ⇒ 操作员手工连的链路仍归它，如 real profile）；
- `MavlinkVehicleAdapter::set_mocap_source_active(bool, reason)`：**解绑旧 peer**（丢绑定车辆、清 session、
  中止未完成指令、`state_store_.remove_drone` 让它立刻离开发布模型），但**保留 slot 注册与 socket**
  （切回来时下一拍心跳就重新绑定，无需操作员动作）；解绑时**不动** `remote_peer_available_`——
  清了它会让固定 udpout 链路永远拒绝来帧（单向门，实测踩过）；
- `BridgeManager::apply_mocap_source(..., source_aircraft_endpoint)`：先决定归属、再逐条应用，
  结果里带 `adapters[].source_active` / `released_links` / `source_aircraft_endpoint`；
- `adapter.status` 的 `transport.mocap.source_active`（与 `effective_source.source_active`）供前端读。

### (b) 切换后旧源的无人机不消失 —— 已修

- **后端**：就是 (a) 的解绑（发布模型里立刻没有旧源的飞机，不再等 `peer_timeout_ms` + 保留窗口）；
- **前端**：新增 `src/js/ui/mocap-source-presence.mjs`，两条信号并用——
  1. `source_active === false` 的链路，其 slot 直接判为陈旧；
  2. 切**离**某源时它的链路是被**移除**的，所以还要用"客户端见过的链路 slot"来识别已消失的链路。
  源身份（`mode|profile|health|expected_drone_id`）一变化就立刻回收，不等下一帧；没有链路信息的无人机
  （模拟机、旧后端）**永不回收**，避免误删。`tests/drone-presence.test.mjs` 覆盖两个方向。

### (C) 顺带修的模组侧回归：第一架飞机丢了 sysid 54 —— 已修

`VirtualDroneFleet` 把 `nextSystemId` 从 1 开始发，于是 `minecraft_drone_01` 广播的是 **sysid 1**，
而后端虚拟链路按文档期望 **54/1**（`MocapSourceSwitch.h` 的内置 profile、模组 README 都写 54/1）：

```text
14561 链路: received 2487 包全被拒, frame_counts.heartbeat=57, heartbeat_seen=false,
           link_state=awaiting_peer  → 虚拟飞机永远不出现在列表里
```

修复：`VirtualDroneFleet.FIRST_SYSTEM_ID = 54`（后续 55、56…），`VirtualDroneFleetTest` 同步。
**这条不改，§2 的"切虚拟只剩 minecraft_drone_01"永远不可能通过。**

### 实测（2026-09-17，现场 8080 + 真机 + 游戏 + relay 全在线）

用 `scripts/mocap-source-live-check.mjs`（对**运行中**的后端发 `connect_mocap_source` 并读链路/飞机列表）：

| 步骤 | 后端门禁（effective_source） | 真机链路 | 虚拟链路 | 界面飞机列表 |
| --- | --- | --- | --- | --- |
| 起始 | real/real_mocap @127.0.0.1:15151 id=54 | 绑定 sysid 1 | — | `real_drone_001_001_ip54` |
| 切虚拟 | virtual/minecraft_virtual_mocap @18151 id=minecraft_drone_01 | **present 但 source_active=false、未绑定** | 绑定 **sysid 54** | **只剩 `minecraft_drone_01`** |
| 切真实 | real/real_mocap @15151 id=54 | 重新绑定 sysid 1（无需操作员动作） | released | **只剩 `real_drone_001_001_ip54`** |

隔离假对端 `node scripts/mocap-source-switch-check.mjs`：**47/47**（新增 3 项归属检查：
释放操作员链路、旧源飞机离开发布模型、切回来自动重绑）；`ctest` 12/12；前端 `npm run test:frontend` 176/176。

## 3. 多机适配：已完成 / 剩余

### 3.5 本轮已完成（模组侧，未提交）

| 步骤 | 落地内容 |
| --- | --- |
| 世界控制器按 id 索引实体 | `DroneWorldController` 用 `Map<String, DroneEntity> entities`（插入序）替代单个 `entity` 字段：`ensureEntity(snapshot)` 按 `droneId` 生成（并 `setDroneId`），`simulateStep` / `entityPosition(droneId)` / `resetOrigin` / `isBoxFree(entity, box)` 全部按那一架走；新增 `entityFor(id)` / `entityDroneIds()` / `releaseDrone(id)` / `retainDrones(liveIds)` 与 `close()` 全清 |
| 放置/收回按机绑定 | `VirtualDroneManager`：`tick()` 走**整个机队**（每机各自 adopt 世界位置、tick、撞机各自 `crash()`）；`placeNewDrone(x,y,z)` 新增一架并返回 `PlacementOutcome(result, droneId)`；`resetFlightOrigin(player, droneId)` 只收回点中的那一架（`resetFlightOrigin(player)` 仍指主机，命令路径不变）。`DronePlacementItem` / `DronePlacementHook.placeOn` / `DroneEntity.interact` 都改成按机：右键方块新增（满 8 架报 `FLEET_FULL`），右键某架收回那一架 |
| 自检 | `VirtualSystemSelfTest` 新增 "fleet keeps per-drone identity and motion"（两架 id/sysid 互不相同、只动被命令的那架、只重置被点的那架），JUnit 11/11 |

`gradlew build --offline`：**155 项全绿**（含新增自检）。**尚未在游戏里点过放置交互**——这需要操作者右键测试。

### 3.6 本轮又完成：第二架飞机能被后端发现并命令（2026-09-17 现场）

操作者报告"新飞机扫描不出来"。根因是**一条链路只当一个飞机**的三处遗留：

| 位置 | 问题 | 修复 |
| --- | --- | --- |
| 模组 `MavlinkTransport` | 每帧都用 `droneManager.snapshot()`，**只有主机遥测被发出去** | 周期遥测按机队逐机发送（各自 sysid/compid）；`MavlinkOutboundMessage` 带上"以谁的身份回话"；`fleetSnapshots()` 作为可测的接缝 |
| 模组 `VirtualAutopilot` | 命令路径只认"那架"飞机（`VirtualFlightController` 单机接口） | 按**报文里的 target**（不是帧头 sysid——帧头是地面站自己的 id）解析到具体飞机：`targetVehicle(targetSystem, targetComponent)`，target=0 保留"主机"语义；新增 `VirtualFlightController.vehicleForSystemId()` + `VirtualDroneHandle`（`VirtualDroneState` 的按机门面） |
| 后端 | 健康信标已经带 `drones[]`，但后端只读单机字段 | `adopt_advertised_fleet()`：信标广告的每架按 id+sysid **自动注册显式绑定**，所以第二架叫 `minecraft_drone_02`（而不是自动槽位名），并出现在发布模型里 |
| 后端 | `attitude_stream_requested_`、`last_estimator_parameters_`、`last_battery_parameters_`、`last_guided_parameters_` 都是**链路级** | 前两个移到 `BoundVehicle`（每架都要自己的 `EK3_SRC1_*` 读数）；三个参数表移到 `BoundVehicle`（不然最后回话的那架替所有机决定估计器配置） |
| 后端 | 单机 `last_source_pose` 被当成**每架**的动捕位姿做一致性交叉检查 | `mocap_pose_for_slot_locked()`：优先用信标 `drones[]` 里**那一架**的位姿，没有机队块时回退单机位姿 |

现场实测（游戏 + 后端都在跑，虚拟源）：

```text
slots: minecraft_drone_01@54 (session active), minecraft_drone_02@55 (session active)
drones 发布模型: minecraft_drone_01, minecraft_drone_02
minecraft_drone_01: position_xy/z/yaw_source=6, link_health=true, state=stable, 准入 guided_required
minecraft_drone_02: position_xy/z/yaw_source=6, link_health=true, state=stable
模组日志: "MAVLink command 511/512 completed" 对 minecraft_drone_01 15 条、minecraft_drone_02 15 条
```

隔离假对端 `node scripts/mocap-source-switch-check.mjs`：**51/51**（新增 4 项机队检查：
第二架按自己的 id 被发现、进发布模型、不影响第一架、注册事件可审计）；`ctest` 12/12；模组 **162 项**（新增
`VirtualAutopilotFleetTest` 6 项：按 target 路由、ACK 以被命令的那架身份发出、未知 sysid 丢弃、广播到主机、
setpoint 只动被命令的那架、每机一帧遥测）。

### 3.7 仍然剩余

| 步骤 | 说明 / 指针 |
| --- | --- |
| 断开链路后的提示 | 断开最后一条链路会让源进入 HOLD（设计如此），面板目前没有"位置转发已暂停"的显式提示，只能从行为看出来（§3.12） |
| 撞机锁存的显示 | 后端的 `safety_latched` 语义仍在（真实飞机由飞控/中继上报，后端按机读，§3.13）；虚拟飞机现在不会自动锁存（§3.15），面板上没有"已锁定 / 怎么解除"的显式提示 |
| 玩家实例隔离 | 同一个世界被两个游戏实例加载时两边会互相覆盖存档（§3.14 末尾），目前只能人工关掉多余实例 |

## 3.16 ★三架同时飞：端到端验收通过（2026-09-18 现场）

三架（`minecraft_drone_01/02/03`，sysid 54/55/56）同时起飞、各飞各的航点、其中一架高速撞场地边缘：

```text
1. 三架 takeoff 全部 tx_only → 全部 GUIDED / armed / flying
2. 三架各自发航点：01→(5,5)  02→(-5,5)  03→(0,-6)
3. 结果（约 30 s 后）：
   01: 距自己目标 0.00 m ✓ | 距 02 10.00 m，距 03 12.08 m
   02: 距自己目标 0.00 m ✓ | 距 01 10.00 m，距 03 12.08 m
   03: 距自己目标 0.00 m ✓ | 距 01 12.08 m，距 02 12.08 m
   每架都到了【自己】的目标，没有串台
4. 把 02 派去撞场地边缘（42 m 外）：
   02 状态 = GUIDED / armed / flying（硬接触不付出任何代价）
   01 偏移 0.00 m、03 偏移 0.00 m（各自稳在原位，完全不受影响）
   02 撞完仍然可收指令（tx_only）
日志：无 crash / hard contact 行；各机命令计数 01:22 / 02:30 / 03:22（各自独立处理）
```

## 3.8 飞机存盘：两处坑（2026-09-17 现场）

操作者要求"实体需要存盘"，第一版做了 `DroneEntity` 的 NBT + `shouldBeSaved()`，**重启后第二架依然消失**。
用 `tmp-region-scan`（解压 region 文件搜 `MiniDroneId`）证明：**实体从来没被写进存档**（2808 个区块全解压，0 命中）。
同时 `SERVER_STOPPING` 里 `DroneWorldController.close()` 会 `discard()` 实体 —— 那一刻正好在存档之前，
所以就算实体能存，也会先被删掉。

最终做法（不依赖实体 NBT 的存盘语义，走模组已有的 `SavedData` 惯例）：

| 位置 | 内容 |
| --- | --- |
| `world/VirtualFleetSavedData`（新） | `mini_drone_fleet`：每机 `drone_id` / `system_id` / 局部 NED 位置；`DataFixTypes.LEVEL`；`put/remove/clear` 带脏标记 |
| `VirtualDroneManager` | 构造时 `computeIfAbsent` 取存盘；开局 100 tick 内反复 `adoptWorldFleet()`（区块加载有先后，扫一次会漏）；`placeNewDrone` 与每 tick 结束时 `rememberDrone()` 写回；恢复时按存盘 id/sysid `fleet.restore(...)` 并回填 NED 位置 |
| `DroneWorldController` | `close()` 改为**只放手不删除**（实体属于世界，删了就是删掉正要写进存档的东西）；新增 `droneEntitiesInWorld()` / `adoptExistingDrones()` 供开局认领；`ensureEntity` 找不到实体时先在世界里找同 id 的实体再决定生成 |
| `VirtualDroneFleet.restore(id, sysid, model)` | 按存盘身份恢复，并让后续发放的 id/sysid 越过已恢复的（不会撞号）；`indexFromId()` 解析 `minecraft_drone_NN` |

实测（现场）：

```text
放好第二架 → 退出游戏（存档） → 重启游戏：
  [15:59:42] Adopted the saved drone minecraft_drone_02
  [15:59:43] Spawned minecraft_drone_01 / Spawned minecraft_drone_02
后端: published minecraft_drone_01, minecraft_drone_02; slots @54 @55 都 active;
      两架 set_pva_target 准入都到 armed_required（动捕交叉检查误差 0）
```

## 3.9 让第二架能收指令：三处"一条链路只当一个飞机"（2026-09-17 现场）

| 位置 | 问题 | 修复 |
| --- | --- | --- |
| 后端 `pending_parameter_requests_` | 是**链路级**一个队列：第二架请求自己的流参数时把第一架待发的读清空了，被清空的那架永远拿不到 `EK3_SRC1_*` | 队列移到 `BoundVehicle`（`parameter_requests` + `next_parameter_request_wall_time_us`），每机各自排队、各自节流 |
| 后端 `mocap_pose_for_slot_locked()` | 机队块的位姿是**源坐标系**（`(-east, -north, -down)`），而一致性交叉检查要 NED——直接当 NED 用会把飞机算成"转了 90°、差 2 m" | 机队条目读出后做轴变换 `NED = (-y, -x, z)`；单机回退分支仍用已经是 NED 的 `last_forwarded_pose` |
| 后端 `admission` | 只回一句"没稳定融合外部导航"，看不出是哪个输入为假 | `data.admission.mocap_gate` 带上该机的 `checks` 与 `estimator_consistency`（判据来自**被命令的那一架**） |

模组侧对应的是 §3.6 的按机遥测 + 按 target 路由（其中"帧头是发送方、不是目标"这一条踩过一次：
按 `frame.systemId()` 解析会把后端所有命令丢掉）。

## 3.10 第二架不能飞航点 + 没有碰撞（2026-09-17 现场，又一轮）

### 航点：元数据被记在"链路的主槽位"上

后端逐机放行没问题（实测：给 `minecraft_drone_02` 发 `goto_waypoint` → `target_resolution.system_id=55`、
准入 `ready`、`tx_only`，那一架确实飞到目标，另一架不动）。挡在前端的是
**`updateMocapTelemetryState` 只把动捕状态记在链路的 `binding.slot_drone_id`（＝第一架）上**，
第二架因此"没有新鲜动捕"，RTS 地图点地移动被 `mocap_required` 拦下。

| 位置 | 修复 |
| --- | --- |
| 后端 `describe()` 的 `binding.discovered_slots[]` | 每个槽位新增 `mocap` 块：`beacon_received` / `reported_healthy` / `safety_latched` / `tracking_age_ms` / `beacon_age_ms` / **该机自己的 `position_ned_m`**（用 `mocap_pose_for_slot_locked`） |
| 前端 `backend-connection-controller.mjs` | 按 `discovered_slots[].slot_drone_id` 逐机记录（`storeMocapTelemetry`，槽位块覆盖链路块）；没有槽位列表时保持原来的单机行为 |
| 测试 | 新增 `tests/mocap-fleet-telemetry.test.mjs`（4 项：两架都记录、姿势各归各、缺槽位块回退链路块、陈旧源永不 fresh） |

### 碰撞：物理步只认方块，飞机和玩家都不是方块

`DroneWorldController` 的 `PhysicsStep.resolve` 原来只问"这个盒子有没有方块"，所以两架飞机互相穿过、
玩家也穿过去。现在谓词换成 `isBoxFreeForDrone(self, box)`：**除方块外，其它无人机与玩家都算墙**
（`AABB.intersects`），于是同一套轴分离逻辑给出"停在它旁边、沿它滑动、报告 blocked 轴"。

实测（两架都 GUIDED/armed/悬停，给第一架发一个**穿过**第二架的目标点）：

```text
closest_centre_distance_m = 0.288      # 接触距离 = 0.9 方块 × 0.25 m/方块 = 0.225 m
flew_through = false                   # 没有穿过去
target_moved_m = 0                     # 被撞的那一架没动
[16:37:27] Virtual drone minecraft_drone_01 crashed into the world at NED (1.29, -0.18, -1.00)
```

即以速度撞上另一架会被判为 **CRASH**（和撞墙同一套 `ImpactModel`），掉到地上；被撞的那一架不受影响。
⚠️ **单位提醒**：遥测/世界坐标是**米**，Minecraft 实体盒是**方块**；0.25 m/方块时 0.9 方块的接触距离
＝ 0.225 m —— 别把两者混着比较（我第一版就是这么误判"碰撞没生效"的）。

## 3.11 "搜索里看不到虚拟飞机的链路"（2026-09-17 现场，又一轮）

**先看清现场发生了什么**：日志里

```text
16:40:13 Accepted command `land`                   from frontend.control.panel
16:40:25 Accepted command `disconnect_mavlink_drone` from frontend.drone_discovery
16:40:25 [adapter:mavlink.adapter.disconnected] MAVLink adapter disconnected by operator selection
16:40:27 Accepted command `disconnect_mavlink_drone` from frontend.drone_discovery
```

即**两条链路都被前端（发现面板）断开了**——这正是它自己的 断开 按钮的 issuer。断开后
`adapter_snapshot()` 变成 `adapter_family=unconfigured / adapter_id=none`，于是多链路面板**一条都不显示**，
发布模型里的两架也随之消失（这一点是对的：链路没了就不该再有那两架）。

**两个真问题**：

1. **搜索是 WLAN 扫描，永远找不到回环链路**。虚拟飞机的链路是 `udpin://127.0.0.1:14561`，
   由"选中虚拟动捕源"创建（R2），不是网络上的设备。所以"在搜索里找它"必然落空。
   - 修复：选中的源若声明了飞机链路，它就作为**候选**出现在搜索/多链路列表里
     （`origin: mocap_source_aircraft_link`，带 endpoint/sysid/compid/bind id），可以直接 接入。
     `tests/discovery-source-link.test.mjs` 3 项覆盖（会加入、不重复、源不声明则不加）。
2. **断开之后没有"接回来"的操作**（只有把源切走再切回来）。现在**重新选中同一个源也会补齐它的飞机链路**
   ——`DroneServerControl` 的 `ensure_mocap_source_aircraft_link` 本来就跑在"切换/未切换"两条路径上，
   本次现场用 `connect_mocap_source`（虚拟源，选择未变）验证：`udpin://127.0.0.1:14561` 与两架一起回来了。

**给操作者的最短恢复路径**：设置 → 动捕源 → 再点一次虚拟源（或"搜索"列表里点那条本机链路 → 接入）。

## 3.12 ★"能起飞但不能飞航点"的真正原因：HOLD 了却没人 RESUME（2026-09-17 现场）

现场症状是**最容易被误导的那种**：`goto_waypoint` 一路 `Command admitted` → `tx_only`，准入 `mocap_gate.checks` 全绿，
但飞机一动不动（10 s 内坐标一位没变）。

根因在**两端不对称**：

| 动作 | 后端行为 |
| --- | --- |
| 断开最后一条飞机链路（`disconnect_mavlink_drone`，不含 armed） | 给动捕源发 **`VLT_RELAY_HOLD_FORWARDING_V1`**（"没有链路时不接收位置"） |
| 操作者点"接入"链路（`connect_mavlink_drone`） | 发 `VLT_RELAY_RESUME_FORWARDING_V1` ✓ |
| **重选动捕源把飞机链路带回来（R2 `ensure_mocap_source_aircraft_link`）** | **什么都没发** ✗ |

模组侧 `ForwardingHold.acceptsSetpoints()` 为假时**只丢 setpoint，不丢命令**——所以 `takeoff`/`arm` 照常成功
（这就是"可以起飞"），航点类命令却全部被静默忽略。

修复（`DroneServerControl.inc`）：把 RESUME 抽成 `DroneServer::resume_mocap_forwarding(endpoint, reason)`，
**R2 的飞机链路每次（重新）建立后都调用**，`connect_mavlink_drone` 也改走同一个函数（失败时补 `mocap.forwarding_resume_failed` 警告事件）。

实测：

```text
重选虚拟源 → 事件 mocap.forwarding_resumed :: Motion-capture position forwarding resumed for the reconnected vehicle link
同一架飞机发 +2 m 航点：x 0.353 → 2.353（正好 2 m），此前同样命令 10 s 内纹丝不动
```

## 3.13 按机安全锁存 + 机队身份过滤（§3.7 收尾）

| 位置 | 改动 |
| --- | --- |
| `mocap_safety_latched_for_slot_locked(slot)`（新，`MocapRecovery.inc`） | 机队块里**那一架**的 `safety_latched`；没有该机条目时回落到单机字段；`nullopt`＝源没表态，由调用方决定松/紧 |
| 调用点 | 准入 `evaluate_command_admission_locked`（缺失时仍**紧**：`value_or(true)`）、`takeoff_stability_snapshot_locked`（→ `checks.mocap_safety_not_latched`）、`prepare_attitude_takeoff_fallback_locked`、两条 attitude fallback、`dispatch_command` 的 `mocap_safety_clear` |
| `discovered_slots[].mocap.safety_latched` | 改成该机自己的锁存（面板/前端按机显示） |
| `mocap_identity_matches(...)`（新，纯策略，放 `include/bridge/MocapSourceSwitch.h`） | 信标单字段 `expected_drone_id` **或**机队块 `drones[].drone_id` 命中即通过；`handle_mocap_health_beacon` 的过滤改用它（读**收到的**信标，不是上一次的） |
| 测试 | `backend/tests/MocapSourceSwitchTest.cpp` 新增 7 项断言（`ctest` 12/12 全绿） |

现场实测（两个方向都验过）：

```text
第一架被我撞机锁存 → 它的准入 = mocap_safety_latched；同一时刻第二架 checks.mocap_safety_not_latched = true（互不牵连）
用 --mavlink-mocap-expected-drone-id=minecraft_drone_02 启动（信标单字段仍是 _01）→ 第二架照样被发现 ✓
```

## 3.14 ★第二、三架"看不到/留不住"：前端按主槽位算所有权（2026-09-17 现场）

操作者报"01 起飞失败，而且 01 没保存住"。查下来是**两件互不相干的事**：

### (a) 起飞失败 = 撞机锁存（设计如此）

`minecraft_drone_01` 在 §3.10 的撞机测试里被我撞过 → 模组把它的 `safety_latched` 置位 →
后端按 §3.13 的按机判据拒绝起飞（`mocap_safety_latched`）。清除方式**只在世界里**：
右键那架飞机（收回＝`resetFlightOrigin` → `clearSafetyLatch`）或 `/minidrone drone reset`；
另外**锁存不进存档**，所以世界重载也会自然复位。日志里没有 `Collect for ... returned` 行 → 当时没有清过。

### (b) "留不住" = 前端的动捕在场过滤只认主槽位 ✗✗（真 bug）

`src/js/ui/mocap-source-presence.mjs` 的 `getMocapLinkSlots()` 原来只读 `binding.slot_drone_id`
（＝链路的主槽位 `minecraft_drone_01`）。它同时又被调用方告知"这条链路曾经声明过 01/02/03"，
于是 **02/03 被判成"已释放"并从列表里删掉**——链路自己携带的机队（`binding.discovered_slots`）根本没看。
表现就是：新放的飞机出现一下、随后消失，列表里只剩 01（"01 没保存住"的观感即由此而来）。

修复：`linkSlotDroneIds()` 把**主槽位 + `discovered_slots[]` 全部**算作该链路声明过的飞机；
释放方向同理（切走一个机队源会回收它全部飞机）。
`tests/drone-presence.test.mjs` 新增 2 项（机队链路全部保留 / 切走后全部回收），前端 **185/185**。

现场核对（用应用自己的模型代码复现面板数据）：
**修复前** `{"drones":["minecraft_drone_01"],"removedIds":["minecraft_drone_02","minecraft_drone_03"]}`
**修复后** `{"drones":["minecraft_drone_01","minecraft_drone_02","minecraft_drone_03"],"removedIds":[]}`
存档侧无问题：`mini_drone_fleet.dat` 里三架俱全（sysid 54/55/56 + 各自的 NED）。

### ⚠️ 另外发现：同一个世界被两个游戏实例加载

现场同时存在两个 `java.exe`：`22372`（本 agent 启动，带 `-Dmini_drone.*`，**占着 14601/18152**，是真正在工作那个）
和 `19128`（PCL 自己启动，**没带属性**）。第二个实例会**绑不上模组的 UDP 端口**，若它也加载同一个世界，
两边会各自保存同一份存档，**后写的覆盖先写的**——做多机验收前请先关掉多余实例。

## 3.15 ★碰撞＝普通物理：不损坏、不锁存（2026-09-17，按操作者要求改）

操作者要求两轮："撞击不要直接导致锁定，尽量避免这种硬编程，暂时处理成我的世界通用的实体碰撞" →
追问后明确 **"撞墙也不锁（全部当普通碰撞）"**。

原来（§3.10 引入、并沿用单机时代的损坏模型）：任何被挡住的轴都喂给 `ImpactModel`，
速度超阈值就是 CRASH → `VirtualDroneState.crash()` → 解除锁死 + 从撞点坠落 + `safety_latched = true`
→ 后端按机拒发指令（§3.13），操作者的飞机就"锁"住了。

现在（`DroneWorldController` / `VirtualDroneManager`）：

| 判定 | 行为 |
| --- | --- |
| 被**方块**或**实体**挡住 | 顶住 / 沿面滑动；`ImpactModel` 只用来描述"碰得多重"，且**上限封在 CONTACT**（`contactOnly()`）——碰撞不能摧毁任何东西 |
| 被**实体**挡住时 | 额外把被撞的那架**推开**（`nudgeBumpedDrones`：推开量 = 步长的一半、上限 0.2 方块），被推的一架在自己的物理步里采纳偏移（`entityPosition`） |
| 玩家 | 仍只靠 vanilla 的 `push`（玩家移动触发），模组不推玩家 |
| `crash()` / `safety_latched` | **不再被碰撞触发**（`VirtualDroneManager` 里那条分支现在只记一条 `took a hard contact` 日志、不复位不锁存）；模组侧 `crash()` 的单元测试仍测状态机本身，但世界里没有任何路径调用它 |

判定用"**想去的位置**是否与其它实体相交"（不是被挡下后的位置——那时已经刚好不重叠），水平/垂直共用。

实测（现场，两架都 GUIDED/armed 悬停）：

```text
第一架撞向第二架：closest = 0.227 m（接触距离 0.225 m）、passed_through = false、
                 被撞那架被推开 1.223 m、两架都仍 GUIDED/armed/flying、无 crash 日志
第二架全速撞向场地边缘：飞了 27.81 m（目标 42.4 m）后停住，
                 state = GUIDED/armed/flying、safety_latch_clear = true、仍可收指令、无 crash 日志
```

> 代价说明：虚拟飞机现在**不会**因为撞墙而坠毁了（看起来像"撞上去停住、擦着墙走"）。
> 真实飞机的保护逻辑不在这里——它由飞控/中继自己上报 `safety_latched`，后端 §3.13 的按机读取照常生效。

## 3.17 ★高空急停穿地板、无限下落（2026-09-21 现场）

操作者报："高空急停后飞机直接穿过地板，无限制下落"。

**根因是单位换算撞上"大位移免检"**：世界是 `metres_per_block=0.25`，模组的下落终速 8 m/s 在方块里
就是 **32 格/秒 → 1.6 格/刻**，超过 `MAX_PHYSICS_STEP_M = 1.5`；而当时的代码把"超过阈值"解释成
**"搬运（生成/手放）"，直接位移、完全不问世界**——于是高速下落**从一开始就整段跳过碰撞** ✗，
穿地之后永远落下去 ✗。

| 位置 | 修复 |
| --- | --- |
| `sim/PhysicsStep.resolveSwept(...)`（新，纯函数） | 超过阈值不再免检：把一步**切成 ≤`maxStepLength` 的多段依次解算**，blocked 标志按位累积；只有超过 `maxSubSteps`（64）段——那才是真正的生成/手放——才按搬运直接落位 |
| `world/DroneWorldController.simulateStep` | 改用它（步长 = `MAX_PHYSICS_STEP_M`、预算 = 64）；**世界底面也算实心**（钳在 `minBuildHeight` 之上），任何情况下都不可能往世界外掉 |
| `world/DroneWorldController.liftOutOfBlocks`（新） | 从存档恢复时，如果飞机原来的位置**在方块里**（例如被这个 bug 埋了），把它抬到上方第一个放得下的位置，并只记一条日志 |

⚠️ **踩过一次的坑（值得记）**：第一版修复只改了**仿真**里的位置（`drone.setLocalPosition`），
下一帧就被世界拉回地下——**位置的权威是世界/实体**（`entityPosition()` 每 tick 都把实体位置采纳进仿真）。
所以纠正必须作用在**实体**上，仿真会自己跟上来。

实测：

```text
存档恢复： [16:10:20] Lifted minecraft_drone_02 out of the ground: its saved spot was inside blocks
           三架 y = 0.00（02 被抬出来，且只记一条日志）

高空急停： 01 号机 set_target_altitude 12 m → 实测 11.88 m → emergency_stop
           高度轨迹 0s:11.88 → 1s:5.61 → 2s:0.00 → … → 30s:0.00
           lowest_height_m = 0.001、stopped_on_the_floor = true、状态 standby（不再无限下落）

单测：     PhysicsStepTest 11 项全绿（新增 4 项：长距下落停在floor、长距撞墙不穿墙、
           短步与 resolve 等价、超预算才当搬运）
```

## 3.18 PVA（位置/速度/加速度）指令与"飞 8 字"测试（2026-09-21）

操作者要求："看一下主项目对 PVA 指令的适配，让模组里的飞机能执行 PVA，然后写个简易测试，就飞 8 字。"

### 模组侧：本来就完整，无需改动

`VirtualAutopilot.handlePositionTarget` → `toLocalSetpoint` 把三个通道都解出来
（`LocalSetpoint.Axis.set(positionSet, position, velocitySet, velocity, accelerationSet, acceleration)`），
`VirtualDroneState` 每轴分别应用并带前馈：
`feedForward += velocity.value(axis)`、`+= acceleration.value(axis) * TICK_SECONDS`；
`yaw`/`yaw_rate`（type_mask 0x0400/0x0800）也接在 yaw 通道上。`set_pva_target` 与 `goto_waypoint`
共用同一套准入（§3.13 的按机判据）。

### 主项目侧：找到三个真问题（都在工具里，不在模组/后端协议里）

| 问题 | 表现 | 修复 |
| --- | --- | --- |
| 1. 键名不匹配 | `tools/pva/flight-test.mjs` 发 `velocity: {n,e,d}` / `acceleration: {n,e,d}`，而 `PositionTargetPva.h` 只认 `{x,y,z}`（或扁平 `vx/vy/vz`）→ **每一帧 PVA 都被 `invalid_params` 拒绝**（"velocity requires all three components x, y and z"） | 发送处翻译：`{x: vel.n, y: vel.e, z: vel.d}`（计划构造器内部仍用 n/e/d，只在一处过线） |
| 2. 适配器快照只认单链路 | 读 `payload.data.adapter.binding`；两条链路时快照是 multi-link 根（`adapters[]`）→ `binding` 取不到 → 工具一直 `timeout waiting for telemetry and adapter status` | 兼容两种形状，按 `--drone` 选中**承载该机的那条链路与其 slot**；并用该 slot 自己的 `mocap.position_ned_m`（虚拟机还用它当 FC 位置——虚拟机的飞控位置本来就等于动捕位置） |
| 3. 起飞后过早开始相位 | 起飞只等 `mocapAlt >= 0.4`，而第一个相位的高度门限是 `计划高度-0.35` → 还在爬升就判 `altitude … in settle` 中止 | 等到**计划高度**再开始相位 |

另外新增 `path_error_m`（与同一时刻期望点的距离）：8 字会穿过自己的圆心，`radial_error_m` 对它没有意义。

### 8 字实测（虚拟 01 号机，r=1.2 m、标称 0.35 m/s、2 圈、1 m 高）

```text
254 帧 set_pva_target，全部 tx_only（0 拒绝）
path_error_m   : mean 0.117 m、p90 0.206 m、max 0.342 m
altitude_error : mean 0.000 m、min 0.000 m
航迹自检       : north −1.21..+1.21 m、east −1.23..+1.26 m；east 在 2 圈内变号 5 次；
                 |north|<0.15 m 时 east 在两侧来回（0.24 / −0.23 / 0.13 / −0.30 …）→ 确实穿过交点画 8 字
trace          : logs/runtime/pva-figure-eight-<stamp>.jsonl
```

复现命令（虚拟机无需 `--execute-real/--operator-present/--confirm-real`）：

```powershell
node tools/pva/flight-test.mjs --scenario=figure-eight --drone=minecraft_drone_01 `
  --radius-m=1.2 --speed-mps=0.35 --revolutions=2 --alt-base-m=1.0 --takeoff-alt-m=1.0
```

## 3.19 ★姿态：游戏里与监控视图"相反"的原因（2026-09-21）

操作者对比模型姿态，发现"游戏中的模型和监控系统相反"，并怀疑两边坐标系定义不同。

### 结论：坐标系确实不同，但那是**双方一致的镜像约定**；错的是模组的俯仰符号

- 约定（`main-backend-compatibility.md:273/310`）：场景/世界轴 **x = −east、z = −north**（与 Minecraft 一致）。
  两边都遵守：前端 `mocapNedPositionToWorldPosition()`（`(-east, -down, -north)`）；
  模组 `originX − east`、`originZ − north`。
- 前端姿态也做同一套基变换：`mavlinkNedQuaternionToSceneQuaternion()` 把四元数矢量部分
  按 `(N,E,D) → (−Z,+X,−Y)` 重表（即镜像 ✓）。
- **模组把镜像也套在俯仰上却多取了一次负号** ✗：`yaw = 180 − yaw`（正确 ✓，镜像只改航向那一项），
  但 `pitch = −pitchNED` ✗。镜像下"低头"应该还是"低头"，取负就变成"抬头"——
  于是同一架飞机在监控里是低头前进、在游戏里是抬头，看起来就是"姿态相反"。

### 修复与固化

| 位置 | 改动 |
| --- | --- |
| `world/NedWorldTransform.toWorldPose` | `pitch = +Math.toDegrees(pitchRad)`（roll 保持 `+`，yaw 保持 `180 −`）；注释写明"镜像只改航向，俯仰/横滚保号" |
| `NedWorldTransformTest.keepsTheAttitudeSignsTheMonitoringViewUses` | 断言低头（pitch<0）仍是低头、抬头仍是抬头、右倾保号（该测试文件共 6 项，全绿） |

> 只改了**渲染**用的 `WorldPose`：遥测/信标用的姿态来自仿真（NED 原值），因此这次改动不影响任何对外数据。

### 还给操作者的判据

- 航向在两边都是**镜像**的（约定如此）：飞机"向东转"在两个界面里都朝同一个方向转 ✓——两边一致 ✓。
- 若修完发现俯仰变成"反向的另一边"（即本来就该取负），只需把这一行符号改回去 ✓。

## 3.20 ★沿场景 x 轴飞行"没有姿态变化"：横滚从来没写进实体（2026-09-21）

§3.19 修好俯仰符号后，操作者报："沿监控系统的 x 轴往复飞，飞机没有姿态变化了"。

**原因**：姿态有三个通道，只有两个走实体自带的旋转：

| 通道 | 通路 |
| --- | --- |
| yaw | `entity.setYRot(...)`（每 tick ✓） |
| pitch | `entity.setXRot(...)`（每 tick ✓） |
| **roll** | **实体没有这个字段**，只能走同步数据 `ROLL_DEGREES`；`applySnapshot()` 会写，但那只在**实体第一次被放置**时调用 ✗ —— `simulateStep()` 里没有人写它 ✗✗ |

于是：仿真一直在压坡度（`steerTowardTilt(atan(rightAccel/g), -atan(forwardAccel/g))` ✓），
模型却永远水平 ✗。为什么这次才暴露：沿**场景 x 轴**飞是"侧向平移"（场景是 NED 的镜像，
x = −east），在航向不变时侧向平移表现为**横滚** ✓ —— 正好是唯一没接通的通道；
之前前后飞看到的是俯仰（§3.19 刚修的那个符号）✓。

**修复**（两处，都是渲染通路，不动仿真/遥测）：

| 位置 | 改动 |
| --- | --- |
| `entity/DroneEntity.setRollDegrees(float)`（新） | 写 `ROLL_DEGREES` 同步数据；`applySnapshot()` 继续用它 |
| `world/DroneWorldController.simulateStep` | 与 yaw/pitch 并列，每 tick 写 `entity.setRollDegrees(wanted.rollDegrees())` |

**实测**（虚拟 01 号机，沿场景 x 轴跑 3 m，读信标里的 `roll_pitch_yaw_rad`）：

```text
max_roll_deg      : 21.67      ← 仿真确实在压坡度
latest_attitude   : 0.0, 0.0, 0.0   ← 到位后回平
travelled_scene_x : 3.00 m
```

## 3.21 ★场景手性做成设置（2026-09-21，按操作者要求）

排查 §3.19/§3.20 之后确认：**后端 world、模组 Minecraft、机型面板三者轴向完全一致**
（实测 `pose.position = (−east, −down, −north)` 与 NED 逐分量对应；操作者自己的数据也印证：
MC `z↑` ⟺ 面板 `Y↑`、MC `x↑` ⟺ 面板 `X↑`）——唯一的差异在**前端 3D 场景**：
`scene-coordinate-frame.mjs` 里 `SCENE_X_MIRROR = −1` 让场景左转成 x = **+east**，
而游戏/面板/文档约定都是 x = **−east** ✗。一次镜像会同时翻转**东西方向**和**旋转的顺逆时针**，
正是操作者看到的"旋转完全相反"。

操作者要的不是"改一行"，而是**可切换的手性设置**（同一套代码要同时跑 Minecraft 场地和真实房间）：

| 位置 | 改动 |
| --- | --- |
| `scene/scene-coordinate-frame.mjs` | 新增 `SCENE_AXIS_MODE_GAME='game'` / `SCENE_AXIS_MODE_ROOM='room'`、`setSceneAxisMode()`/`getSceneAxisMode()`；位置、速度、姿态三处转换全部走同一个开关 |
| `ui/settings-preferences.mjs` | 新设置 `sceneAxisMode`，**默认 `'game'`**（与游戏一致），非法值回落 |
| `controllers/workbench-controller.mjs` | 设置页 → **交互** → "场景坐标手性"下拉；改动时重新套用场地（清掉尺寸签名后重跑 `updateMocapFieldDimensions`）并记一条运行日志 |
| `controllers/backend-connection-controller.mjs` | 姿态改由 `worldPositionToScenePosition` + 伪矢量规则统一处理 |

⚠️ **姿态不能用"翻一个分量"或"整体取反"糊过去**（我两次都写错过：

- 只翻 x 分量 → 纯航向的四元数 x 分量本来就是 0，**航向根本没被翻** ✗；
- 整个矢量部取反（等价于角度取负）→ 连**俯仰也跟着反了** ✗，镜像不该改变"抬头/低头"。

正确规则：**旋转的轴是伪矢量** —— `axis_scene = det(M) · M · axis_ned`（`M` = NED→场景的基变换矩阵，
`game` 的 `det = −1`、`room` 的 `det = +1`）。这样：
俯仰在两种模式下都保持"抬头是抬头"，而**航向与横滚随镜像一起翻**（它们本来就该翻）。

**测试**：前端 **192/192**。新增 `tests/scene-axis-mode.test.mjs` 4 项；
并更新了 3 个原本写死"默认镜像"的用例（坐标系转换、坐标轴 helper、RTS 点地映射）
和 1 个姿态用例（改成断言"航向指向位置意义上的 east"、"抬头仍是抬头"、"右倾倒向 east"——模式无关）。

## 3.22 场景手性改成"图示模态框"（2026-09-21，按操作者要求）

操作者反馈"下拉选择不够直观，想要点击后弹出模态框，里面图示解释不同参考系三轴怎么组合"。

| 位置 | 改动 |
| --- | --- |
| `scene/scene-coordinate-frame.mjs` | 新增 `SCENE_AXIS_MODE_DESCRIPTIONS` / `getSceneAxisModeDescriptions()`：把每一档的**标签、三轴指向、旋转说明、位置向量、手性（行列式）**集中描述一次，界面与测试共用同一份 |
| `controllers/workbench-controller.mjs` | 设置项从 `<select>` 换成"当前值 + 「对照并修改…」按钮"；新增 `mountSceneAxisModeDialog()` / `openSceneAxisModeDialog()` / `applySceneAxisModeDialog()` / `closeSceneAxisModeDialog()`；`renderSceneAxisDiagram()` 为每一档画一张**俯视罗盘 SVG**（东/西/南/北 + 原点 + "屏幕右=+X、屏幕里=−Z"标注），并列出三轴、说明、`world 位置 → 场景` 的公式行 |
| `styles/main.css` | 模态框、两栏对照卡片、选中态、图示样式（与既有 `runtime-mocap-config-modal` 一致的视觉语言） |
| `tests/scene-axis-dialog.test.mjs` | **图示不能和转换函数说法不一致**：逐档断言"东 → ±X"与 `worldPositionToScenePosition()` 的实际符号一致、向量行与转换一致、行列式与是否镜像一致；另断言两档之间航向首尾互换而俯仰不翻转 |

测试：前端 **194/194**。

## 3.23 轴约定模态框的三个修正（2026-09-21，操作者反馈）

| 反馈 | 原因 / 处理 |
| --- | --- |
| **点按钮不弹模态框** | 我把按钮挂在了设置页的 **`change`** 事件里，而按钮只发 **`click`** ✗ → 改为 `click` 监听（`tests/scene-axis-dialog-open.test.mjs` 覆盖；顺带记下：该页对同一事件类型绑定了**多个** handler，写测试时要全部派发，否则会误判"没绑定"） |
| **"场景坐标手性"这名字不对、说明太长** | 设置项改名 **「轴约定」**，副标题压成一句"场景三轴与实际场地的对应关系"；模态框标题改为"选择场景的轴约定"，运行日志文案同步 |
| **想在机型详情里也看到当前轴约定** | `details-drawer.js` 新增 `renderAxisCompass()`：在「空间与姿态」组、**位置 XYZ 那一行的上方**放一张小罗盘（跟随设置改变东的左右、并显示当前档位与向量行）。位置读数和它用的是同一套轴，放一起才不会又对不上 |

测试：前端 **196/196**。

## 3.24 飞行控制里的"平面位置"跟随轴约定（2026-09-21，操作者要求）

飞行控制区的小地图（`details-mini-map`）之前**直接用 world 坐标画**，所以切到"镜像"档时，
小地图上的飞机和 3D 场景、以及正上方的 `X / Y` 读数会分处两侧 ✗。

| 位置 | 改动 |
| --- | --- |
| `details-drawer.js` `updateMiniMap()` | 标记点改由 `worldPositionToScenePosition()` 求得（小地图画的就是场景轴）；位置标签的 tooltip 补上当前轴约定 |
| `details-drawer.js` `handleMiniMapContextMenu()` | ⚠️ **顺带修掉一个真 bug**：右键小地图反算出的点**原本是 world 坐标，却被当成 scene 坐标**交给 `buildRtsWaypointRequest`（它按场景轴自己解析 x/z）——也就是说**只有"镜像"档下点地移动会飞到对称的镜像位置** ✗。现在统一交**场景坐标**，回调链里的单位只有一个（`drone-panel/index.js` 处补了注释说明） |
| `tests/scene-axis-mode.test.mjs` | 新增 1 项：同一 world 点在两档下的小地图标记必须分处中心两侧且互为镜像（深度轴不变） |

**为什么两档都要对**：地图是给人对着实际场地看的，所以它必须和场景同一套轴；而回调链是唯一的，
不能在两头各自换算。

测试：前端 **197/197**。

## 3.25 选中样式：旋转靶心 → 琥珀色瞄准框（2026-09-22，按操作者提供的截图）

操作者给的参考图是一张瞄准吊舱画面：真实车辆外面套着**琥珀色方框**——
上下两条长横线、每条两端向内有短竖钩、左右两个**朝内的箭头**、左上角一个 **`TARGET`** 小字标签。

原来的实现（`scene/drone-manager/drone.js`）是"深蓝 + 淡蓝"的**双层旋转靶心**：两圈虚线圆环
（12 段、配色交替）+ 一圈角刻度，两个 group 反向旋转。

| 位置 | 改动 |
| --- | --- |
| 常量 | 去掉 `SELECTION_PRIMARY_COLOR/SELECTION_ACCENT_COLOR/SELECTION_TICK_COLOR`，改为 `SELECTION_TARGET_COLOR = 0xf5a524`（琥珀）+ 标签色/文本 + `SELECTION_RETICLE_SIZE = 0.62` |
| `createSelectionRing()` | 由 band+cross 两组圆环改为一个 **reticle 组**：瞄准框 billboard + `TARGET` 标签 |
| `createSelectionBillboard(..., 'reticle', ...)` | 新增瞄准框片元着色器：在 0–1 的 UV 里画两条横线、四个端部竖钩、两条中心短刻度、两个朝内箭头（箭头用 `step()` 画三角形），线宽在归一化坐标里恒定 → **远近观感一致** |
| `createSelectionTargetTag()` | 用 canvas 一次性画好 `TARGET` 文字做成贴图，贴在左上角 |
| `syncSelectionRingTransform()` | 原样保留**相机朝向**（billboard），并把飞机的 **yaw/pitch** 折进去（`YXZ`，roll 不用——横滚会让标签歪掉不可读） |
| 动画 | 删掉 `selectionBandAngle/selectionCrossAngle` 与每帧反向旋转（`tk()` 里那段）：瞄准框**不旋转**，靠跟着机头转来体现姿态 |
| `createOutline()` | 备用的包围盒描边颜色一并改成琥珀（原来引用已删除的 `SELECTION_ACCENT_COLOR`） |

**验证**（headless 建 ring + 姿态同步，`document` 用桩替换）：

```text
ring children        : selection-reticle
material             : ShaderMaterial | amber rgb 0.961, 0.647, 0.141  (= 0xF5A524 ✓)
tag position         : -0.19, 0.24, 0.00                              (左上角 ✓)
follows yaw90/pitch6 : 6.0, 90.0, 0.0                                 (俯仰+航向折入，横滚为 0 ✓)
ring position        : 机身附着 (0, 0.055, 0) / 场景附着 (1, 2.055, 3) ✓
```

测试：前端 **197/197**。

## 3.26 瞄准框：跟飞机的水平面走 + 远处强调 + 呼吸（2026-09-22，操作者反馈）

操作者指出三件事：

1. **框不是"贴相机"的** —— 它要**标定无人机所在的水平面**，所以应始终与飞机的**水平姿态面**同步；
2. **不封口的两条边要指示前后**；
3. **必须有轻微呼吸**，而且要**远处明显、近处不强调**。

| 位置 | 改动 |
| --- | --- |
| `createSelectionRing()` / billboard | **取消 billboard**：几何体转 `-90°`（平躺），`reticleGroup.rotation.order = 'YXZ'`，只写 **yaw（世界垂直轴）+ roll（机体前向轴）**；**pitch 故意不写**（框是"水平基准"，用来对着倾斜的机体读数） |
| 着色器 | 布局改为**机头相对**：sprite 局部 **+Y = 机头方向**，于是两条长边成为**横跨机体的前后两条线**、两个箭头位于机体**前后**并朝内 → 开口方向即前后 ✓ |
| `updateSelectionEmphasis(camera, group)`（新） | 按**相机距离**算 emphasis（2 m→1.0、18 m→2.4、smoothstep）：远处**放大**（屏幕尺寸不至于缩成一个点）并**加粗线宽**；近处两项都回到 1 |
| 呼吸 | `size = 基准 × emphasis × breath`，`breath = 1 ± 0.16·sin(2πt/1.5s)`；呼吸**幅度**随距离淡出（近处仍有，只是更轻）；线宽再乘 `(2 - breath)`，于是近处也能看出脉动 |
| `TARGET` 标签 | 标签单独保持 **billboard**（跟飞机的框会把它转得读不出来），位置改到框的**左上角** |
| 相机传递链 | `app.js` 帧循环 → `droneManager.update(camera)` → `advanceDroneFrame({ camera })` → `drone.update(dt, camera)`；`tests/drone-frame-step.test.mjs` 新增 2 项断言相机被透传（含"没有相机也要能跑"） |

**验证**（headless，姿态 yaw 90 / roll 12 / pitch 4）：

```text
camera   1 m | emphasis 1.00 | size x 1.05 | ink 0.95
camera   2 m | emphasis 1.00 | size x 1.06 | ink 0.94
camera   6 m | emphasis 1.22 | size x 1.30 | ink 1.14
camera  12 m | emphasis 1.96 | size x 2.16 | ink 1.75
camera  18 m | emphasis 2.40 | size x 2.70 | ink 2.10
camera  40 m | emphasis 2.40 | size x 2.70 | ink 2.10   （封顶）
bracket 平躺 = -90°；group rot = 0.0 / 90.0 / 12.0     （pitch 未折入 ✓、yaw 与 roll 生效 ✓）
```

测试：前端 **199/199**。

## 3.27 瞄准框呼吸：改成"两条长边左右开合"（2026-09-22，操作者反馈）

操作者指出：呼吸**不该整体缩放**，应当**不改变大小，只沿飞机左右向外—向内循环移动**。

同时借这次把框的方向校正到与参考图一致：

| 项 | 之前（我理解偏了） | 现在 |
| --- | --- | --- |
| 两条长边 | 横跨机体，分列**前后** | **沿机头方向前后走**，分列**左右两侧**（与参考图的车身两侧一致） |
| 箭头 | 画在**中心线**上（正好压在中心刻度上，看起来像四个尖刺） | 每条长边**两端各一个**、朝内指 ➜ 开口方向即前后 |
| 呼吸 | `size × breath`：整体**放大缩小** | `breath` uniform：两条长边沿**机体左右轴**平移（±0.075 个 sprite 单位，对称开合）——**尺寸恒定** |
| 线宽 | 乘 `(2 - breath)`（把呼吸混进线宽） | 只由距离决定（`emphasis`），呼吸不再影响线宽 |

实现要点：呼吸作用于**着色器里的 `uv.x` 偏移**（`leftRule = 0.185 - breath`、`rightRule = 0.815 + breath`），
所以四角的箭头与刻度都跟着边一起走、四边关系不变；远处 `emphasis` 放大时呼吸幅度**按比例**同步（`breath` 是比例量，
在 sprite 空间里与屏幕尺寸无关）。`prefers-reduced-motion` 下呼吸为 0。

**验证**（近处、一个完整呼吸周期 1.5 s 的四分点）：

```text
t=   0ms | breath +0.0000 | scale 1.000 (size unchanged)
t= 375ms | breath +0.0750 | scale 1.000     ← 向外
t= 750ms | breath  0.0000 | scale 1.000
t=1125ms | breath -0.0750 | scale 1.000     ← 向内
t=1500ms | breath -0.0000 | scale 1.000
远处 24 m：breath ±0.207（按 emphasis 3.2 放大）、scale 3.20、ink 3.20
```

**测试**：`tests/selection-reticle.test.mjs` 5 项（新增/改写两项：箭头分属两条长边且每条两端各一；
呼吸推动 `breath` 且 **scale 恒为 1**）。前端 **204/204**。

## 3.28 瞄准框：箭头改成"前后各一个"、与框同呼吸，并整体放大（2026-09-22）

操作者要求：**整体放大一点（原来太贴飞机）**；**三角形的位置和数量不对**——
不应与线条重叠，应"一个在飞机前方、一个在飞机后方"，并与外框对应地**向前-向后呼吸**。

| 项 | 改动 |
| --- | --- |
| 尺寸 | `SELECTION_RETICLE_SIZE_M` **0.36 → 0.52 m**（先算过间隙：横向跨电机 0.196 m，原尺寸两侧只剩 0.015 m，很紧；0.52 m 时两侧各留 0.066 m） |
| 箭头数量 | 4 → **2** |
| 箭头位置 | 由"每条长边两端各一个"改为**中轴线上、机体正前与正后各一个**，分别朝后/朝前指；不与长边重叠 ✓ |
| 呼吸 | 同一个 `breath`：长边**向左右开合**的同时，两个箭头**向前后同步移动**（`tipY = 0.195 - breath` / `0.805 + breath`）→ 整个框作为**一个形状**开合 |
| 尺寸恒定 | 仍不变（`scale` 始终等于 `emphasis`，与呼吸无关） |

实测（近处）：

```text
bracket 0.52 m | 箭头位于 ±0.159 m（前/后）| 机头前方净空 0.061 m
长边位于 ±0.164 m（左右）| 机翼净空 0.066 m
breath ±0.075 -> 长边左右移 0.019 m，箭头前后移 0.019 m | scale 恒为 1
```

测试：`tests/selection-reticle.test.mjs` 改写为断言**两个箭头都在中轴线（`0.500`）**、
**一个带 `- breath` 一个带 `+ breath`**、且**一个在前一个在后**。前端 **204/204**。

## 3.29 瞄准框：从下看不见 + 不随机体姿态变化（2026-09-22，操作者飞行测试）

操作者飞行测试后发现两件事：**从上能看见、下方是透明的**；**框不随机体姿态变化**。
需求重述：**框与飞机的水平面平行，箭头所在轴 = 飞机前后轴**。

### (a) 从下看不见：单面 quad ✗

框是一个平面 quad，默认 `side: FrontSide`，相机降到它下面时整块被**背面剔除** ✗。
修复：`side: THREE.DoubleSide`（框与 `TARGET` 标签都改），并保留 `depthTest:false`。

### (b) 不随机体姿态变化：用欧拉角建姿态的**坐标系错了** ✗✗

原来用 `eulerOrientation` 拼 `rotation.set(0, yaw, roll)`。问题的关键在 §3.21 里：
**场景轴约定为 `x = −east、z = −north`，相对 NED 是镜像**，于是后端姿态经
`mavlinkNedQuaternionToSceneQuaternion()` 变成**场景系四元数**时，角度已不再是"绕机体轴"的欧拉角 ✗
（world yaw = −scene yaw，roll 也换了符号）——拿它们去摆框，框就会与机体错位。

修复：**改用机体四元数 + 机体轴**，而不是欧拉角：

| 位置 | 改动 |
| --- | --- |
| `drone-manager/index.js` | seed 里新增 `airframeQuaternion`（= 场景系姿态四元数），更新路径一并透传 |
| `drone.js` 构造函数 | `this.airframeQuaternion = data.airframeQuaternion \|\| this.orientation` |
| `drone.js` `updateSelectionBasis()`（新） | 取机体轴 `nose = (0,0,-1)`、`wing = (1,0,0)` 各自乘四元数 → **把 y 归零（取水平投影）** → `wing = nose × down` 重新正交 → `makeBasis(wing, nose, down)`（注意：quad 平躺后其法线落在 **−Y**，所以第三个轴用 down 而不是 up） |
| `getAirframeQuaternion()`（新） | 姿态未知时回落到单位四元数 |

实测（四种姿态，看框自身三轴在场景系里的朝向）：

```text
水平、机头朝北(-Z) | 框+Y(机头轴) 0,0,-1 | 框+X(机翼轴) -1,0,0 | 法线 0,-1,0
机头下压 15°       | 0,0,-1              | -1,0,0              | 0,-1,0   ← 不吃俯仰 ✓
右滚 20°           | 0,0,-1              | -1,0,0              | 0,-1,0   ← 不吃横滚 ✓
偏航 90° + 下压 15 | -1,0,0（随航向转）    | 0,0,1               | 0,-1,0   ← 随航向转、仍水平 ✓
```

**测试**：`tests/selection-reticle.test.mjs` 6 项（新增"双面可见"与"跟随航向且保持水平"；
改写姿态用例：把四种姿态下的**框轴朝向**都断言出来）。前端 **205/205**。

## 3.30 瞄准框：贴合机体姿态面（修正 §3.29 的判断）（2026-09-22）

操作者指出：**框要与飞机的姿态面一致**（不是水平面），而 §3.29 的实现"垂直于飞机 XY 面"。

### 真 bug：quad 被转了 90°，加上为"已转过的 quad"配的基变换 = 竖起来了

`reticle.rotation.x = -Math.PI/2`（本意是"把平躺的 plane 放平"）与 `makeBasis(right, nose, down)`
是**两次互相抵消的旋转**，配上 §3.29 的"水平投影"就变成：quad 法线落在 `(0,0,-1)`——
**垂直于机体 XY 面** ✗。实测（单位姿态）：`quad normal = 0,0,-1`。

现在只有一个地方决定朝向，且语义直接：

| 位置 | 改动 |
| --- | --- |
| `createSelectionRing()` | **去掉** `reticle.rotation.x = -π/2`（quad 保持几何原状，平面完全由 group 基变换决定） |
| `updateSelectionBasis()` | **不再做水平投影**：直接用姿态四元数把机体三轴转出来 —— `nose`、`wing`、`up`，然后 `makeBasis(wing, nose, up)`（quad 几何在 XY 面：+X 跨机翼、+Y 沿机头、法线在机体 +Z） |
| 常量 | `SELECTION_AXIS_UP` 取代 `SELECTION_AXIS_DOWN` |

实测（五种姿态，比较 quad 法线与机体 up、quad +Y 与机体 forward）：

```text
水平             | 法线 0,1,0     | 机体up 0,1,0     | dot 1.000 | +Y 0,0,-1
机头下压 15°     | 法线 0,0.97,-0.26 | 0,0.97,-0.26   | dot 1.000 | +Y 0,-0.26,-0.97
右滚 20°         | 法线 0.34,0.94,0 | 0.34,0.94,0     | dot 1.000 | +Y 0,0,-1
偏航90°+下压15°  | 法线 0,0.97,-0.26 | 0,0.97,-0.26   | dot 1.000 | +Y -1,0,0
下压15°+右滚20°  | 法线 0.34,0.91,-0.24 | 0.34,0.91,-0.24 | dot 1.000 | +Y 0,-0.26,-0.97
```

即：**框的平面 = 机体姿态面（含俯仰/横滚）✓、+Y 轴 = 机体前后轴 ✓**。

**测试**：`tests/selection-reticle.test.mjs` 的"跟随姿态"用例改为对**五种姿态**逐一断言
「法线 = 机体 up」「+Y = 机体 forward」「±X = 机体 right」（与姿态四元数直接推导的值比对，不写死数字），
并新增"quad 自身不带旋转"的断言。前端 **205/205**。

## 3.31 `TARGET` 标签：移到飞机正上方 + 朝下箭头 + 始终面向镜头（2026-09-22）

| 项 | 之前 | 现在 |
| --- | --- | --- |
| 归属 | 挂在**瞄准框组**里（继承机体姿态） | 挂在**ring 上**（`ring.userData.radar`）→ **不继承机体姿态** ✓ |
| 位置 | 框的左上角 `(-0.30, 0.05, -0.38) × 框长` | **飞机正上方**：`(0, SELECTION_TAG_HEIGHT_M=0.42, 0)`，世界系向上 |
| 朝向 | 仅文字面 billboard | **整个标签组 billboard**（`tag.quaternion = camera.quaternion`）→ 文字与箭头一起正对镜头 ✓ |
| 箭头 | 无 | 新增 `selection-target-tag-arrow`：canvas 画的**朝下箭头**（短柄 + 三角头），位于文字下方，**尖端朝飞机** |
| 每帧更新 | `plane.onBeforeRender` | `updateSelectionTag(camera)`：设位置 + 复制相机四元数 |

标签不再是"框的附件"而是"关于这架飞机的注记"——所以它**只随相机转、随飞机平移**，不随飞机姿态倾斜 ✓。

实测（机体 俯仰-15°/偏航40°/横滚-25°，相机在侧上方）：

```text
tag position    : 0, 0.42, 0        ← 世界系正上方，与姿态无关 ✓
tag quaternion  : 与相机四元数完全相等 ✓
文字在箭头上方  : tagPlane.y 0.042 > arrow.y -0.060 ✓
箭头         : 跨 y ∈ [-0.12, 0]，尖端朝飞机 ✓
相机移动时      : 标签跟着转、瞄准框法线不变（dot = 1.0000）✓
```

⚠️ 中途踩坑：这次编辑**误删了 `reticleGroup.add(reticle)`**，导致瞄准框整个不显示（测试立刻报
"the bracket quad exists" 失败）——测试在这里救了场。

**测试**：`tests/selection-reticle.test.mjs` 8 项（新增"标签悬于正上方且正对镜头"、
"相机移动时标签跟随而框不动"；canvas 桩补齐 `fillRect/beginPath/lineTo/...`）。前端 **207/207**。

## 3.32 标签下的箭头改成小等边三角形（2026-09-22）

操作者：箭头太夸张，改成**等边三角或直角**。

| 项 | 之前 | 现在 |
| --- | --- | --- |
| 形状 | 短柄 + 三角头的**箭头** | **等边三角形**（canvas：`moveTo(32,58)` 尖端朝下、`(4.3,6)/(59.7,6)` 平边朝上；宽 = 高 × 2/√3） |
| 尺寸 | 0.12 m 高（比文字还高 143% ✗） | **0.055 m 高 × 0.064 m 宽**（文字纹理的 65% 高 / 32% 宽） |
| 位置 | 文字下方，间隙为 0 | 不变（`text bottom = triangle top`，紧贴但不重叠） |

用 canvas + 纹理而不是裸三角网格，是为了在这么小的尺寸下**照样抗锯齿**，与 `TARGET` 文字同一套观感。

实测：`triangle 0.064 x 0.055 m | equilateral? true | vs text texture: 32% wide, 65% tall`。

测试：前端 **207/207**（既有 8 项瞄准框用例全绿）。

## 3.33 标签：文字居中 + 三角形贴近（2026-09-22）

操作者：**三角形离得有点远**；**"TARGET" 文字不在正中心，向左偏**。

两个都是**同一个根因**：`createTargetLabelCanvas()` 用 `textAlign='left'` 在 `x=4` 手写偏移画字，
而 quad 的高度又按**猜的宽高比 0.42** 定 —— 于是

1. 文字在纹理里**没有居中**（左边距 4px、右边距 ~62px）→ 看起来"向左偏" ✗；
2. 纹理上下留了一大片空白 → 文字浮在 quad 中间，**三角形按 quad 边缘摆放，就离字很远** ✗。

修复：

| 位置 | 改动 |
| --- | --- |
| `createTargetLabelCanvas()` | 用 `measureText()` 的 `actualBoundingBoxAscent/Descent/width` **按文字实际尺寸裁剪画布**（+2px 内边距），并以 `textAlign='left'`、`baseline='alphabetic'` 精确落笔 → **文字恰好填满纹理**；拿不到测量值时回落到居中的整幅画布 |
| `createSelectionTargetTag()` | quad 的宽高比**取自画布本身**（`tagHeight = tagWidth × canvas.height/canvas.width`），不再用猜的比例 |
| 新增 `SELECTION_TAG_GAP_M = 0.008` | 三角形与文字之间留 8 mm，紧贴而不重叠 |
| `SELECTION_TAG_HEIGHT_M` | 0.42 → **0.30 m**（悬挂高度，文字底部实际在飞机上方约 0.38 m） |

实测：

```text
canvas aspect -> quad 0.200 x 0.052 m（文字填满，无空白边）
gap between letters and triangle: 0.0080 m
triangle 0.064 x 0.055 m | 宽度 = 文字的 32%
```

**测试**：新增 1 项"quad 宽高比必须等于画布宽高比"（防止再有人写死比例让文字浮起来），
并把"标签悬于上方"的断言改为**不写死数字**（`x=z=0`、`y>0`）+ 断言**文字与三角形的间距 < 2 cm**。
前端 **208/208**。

## 3.34 标签：屏幕上恒定大小（2026-09-22）

操作者：标签与三角形**离远了还是看不清**，要求"不论距离，在摄像机画面里的相对大小不变"。

原因很直接：标签此前是**世界尺寸**（0.20 m 宽），透视相机按 1/距离 缩小它，所以远了就成一个小点 ✗。

| 位置 | 改动 |
| --- | --- |
| `updateSelectionTag(camera)` | 新增 `tag.scale.setScalar(getTagScreenScale(camera))` |
| `getTagScreenScale(camera)`（新） | `scale = clamp(max(distance, 0.6) / 2.0, 0.3, 24)`；**距离量到标签真正所在的位置**（飞机上方 `SELECTION_TAG_HEIGHT_M`），因为投影的是那一点；用 `SELECTION_TAG_ANCHOR` 复用向量，避免每帧分配 |
| 常量 | `SELECTION_TAG_REFERENCE_M = 2.0`（在该距离为标称大小）、`MIN_DISTANCE_M = 0.6`（太近不再放大，防止糊屏）、`MIN_SCALE = 0.3`、`MAX_SCALE = 24`（可保持恒定到约 48 m） |

实测（60° fov、1080p 投影宽度）：

```text
距离     | 缩放  | 文字 px | 三角形 px
   0.6 m |  0.30 |  93.5  |  29.7
     1 m |  0.50 |  93.5  |  29.7
     2 m |  1.00 |  93.5  |  29.7
     4 m |  2.00 |  93.5  |  29.7
     8 m |  4.00 |  93.5  |  29.7
    16 m |  8.00 |  93.5  |  29.7
    24 m | 12.00 |  93.5  |  29.7
    48 m | 24.00 |  93.5  |  29.7
```

**测试**：新增 2 项——"任意距离屏幕上宽度恒定（±0.5 px）且足够可读（>40 px）"、
"相机贴到标签上时缩放仍有限且有效；无相机时回落到 1"。前端 **210/210**。

## 3.35 标签：大小规则不变，升高幅度取 25%（2026-09-22）

操作者澄清：**大小沿用原来的规则**（即 §3.34 的屏幕尺寸恒定），**只是高度变化幅度缩小**
（"缩小 75%" → `SELECTION_TAG_RISE_FRACTION = 0.25`）。

```js
tag.scale.setScalar(scale);                     // 不变：屏幕上恒定大小
lift = SELECTION_TAG_HEIGHT_M
     + (SELECTION_RING_HEIGHT_M + SELECTION_TAG_HEIGHT_M) * (scale - 1) * 0.25;
```

⚠️ 过程记录：我先按字面把 0.25 试出来，用**固定像素阈值**（>60 px）衡量，觉得"远处只剩 38 px，太贴"，
就擅自改成了 0.5 并回头问操作者——**这是我判断标准错了**。正确判据是**和飞机自身的屏幕尺寸比**：
飞机在 24 m 处只有 7.6 px 高，38 px 净空是它的 **5 倍**，完全够。

修正后的实测（判据 = 净空 ÷ 飞机屏幕尺寸）：

```text
距离     | 标签(px) | 飞机顶部(px) | 三角尖(px) | 净空(px) | 相对机高
   0.6 m |   24.8   |    152.8     |   388.7    |   236.0  |  0.8x
     1 m |   24.7   |     91.7     |   244.8    |   153.1  |  0.8x
     2 m |   24.7   |     45.8     |   136.9    |    91.0  |  1.0x
     4 m |   24.7   |     22.9     |    82.9    |    60.0  |  1.3x
     8 m |   24.7   |     11.5     |    55.9    |    44.5  |  1.9x
    16 m |   24.7   |      5.7     |    42.5    |    36.7  |  3.2x
    24 m |   24.7   |      3.8     |    38.0    |    34.1  |  4.5x
    48 m |   24.5   |      1.9     |    33.1    |    31.2  |  8.2x
```

**测试**：`the tag keeps the same size on screen at any distance` 现在同时断言
①标签屏幕高度恒定（±0.5 px）、②**净空 > 飞机屏幕尺寸的 0.5 倍**（按距离分别计算，不用固定阈值）。
前端 **210/210**。

## 3.36 俯视角下选中框过大 + 标签压住飞机（2026-09-22，操作者反馈）

操作者：**俯视角下适配不行，起飞后框会特别大**。

### 复现与真因

俯视角有**两个**入口，两者都与"按距离定尺寸"这套规则冲突：

| 视图 | 相机 | 尺寸基准 |
| --- | --- | --- |
| 顶视（`setTopView`） | **透视**，但相机高度只有 `max(场宽,场深)×1.2`（默认 4.8 m） | 距离**很近** |
| 战术俯视（`toggleCameraMode`） | **正交** `OrthographicCamera`（视锥高 `2.4`） | **与距离无关** |

实测（透视顶视，飞机从 0 m 升到 4 m，相机在 4.8 m 高）：

```text
高度 | 相机距 | 飞机 px | 框 px | 框占屏
  0 m |  4.80 |    38 |   108 |  10%
  2 m |  2.80 |    65 |   174 |  16%
  3 m |  1.80 |   102 |   270 |  25%
  4 m |  0.80 |   229 |   608 |  56%   ← 占满半个屏幕
```

即：**飞机上升 = 离俯视相机更近**，飞机自身屏幕尺寸暴涨（229 px），而框始终是机体的 **2.65 倍**，
于是跟着涨到 **56%** ✗。正交视图更彻底——`getSelectionEmphasis()` 按距离算的 emphasis 在那里**毫无意义**。

顺带发现第二个 bug：标签"沿世界向上抬起"在俯视下**正对镜头**，投影后偏移为 **0 px** ✗
（实测：0/1/2/3 m 高度，标签与飞机屏幕位置完全重合）。

### 修复

| 位置 | 改动 |
| --- | --- |
| `getViewportSpanAt(point, camera)`（新） | **唯一的尺寸换算口**：透视相机用 `2·d·tan(fov/2)`；**正交相机用视锥高 `top−bottom`（除以 zoom）**，与距离无关 |
| `getTagScreenScale(camera)` | 改为"屏幕占比"表达：`span × SELECTION_TAG_SCREEN_FRACTION / 标签宽`（该常量由原 2 m 基准 + 60° fov 反推，保持既有观感） |
| `getReticleScale(camera, emphasis)`（新） | 在 emphasis 之上加**屏幕占比上限** `SELECTION_RETICLE_MAX_SCREEN_FRACTION = 0.25`：远了该强调，近了不许吃满屏幕 |
| `isTopDownView(camera)`（新） | 用**视方向**判断（`|forward.y| > 0.9`，约偏垂直 26° 内），对透视/正交都成立 |
| `updateSelectionTag` | 俯视时沿**相机 up 轴**抬起，而非世界 up；其余视角仍沿世界 up |

### 修复后实测（四种相机）

```text
视图           | 高度 | 框占屏 | 标签占屏 | 标签离机 px
三分视         |  0 m | 11.7% | 8.10% |   93
三分视         |  4 m | 11.7% | 9.42% | 1589
透视顶视       |  0 m | 10.0% | 7.44% |   78
透视顶视       |  4 m | 25.0% | 7.69% |   48      ← 封顶生效（原 56%）
正交战术俯视   |  0 m | 11.6% | 8.66% |   89
正交战术俯视   |  4 m | 10.8% | 8.66% |   89      ← 与高度无关，符合正交语义
正视图         |  0 m | 10.0% | 8.76% |   94
正视图         |  4 m |  8.7% | 9.57% |  668
```

标签占比在各种视角下稳定在 7.4%–9.6%，且**俯视下不再压在飞机上**（78–48 px 偏移）✓。

**测试**：`tests/selection-reticle.test.mjs` 新增 2 项——"俯视框必须仍是标记（≤26% 屏高，且不塌缩）"、
"俯视标签必须让开飞机（>20 px）"，并改用**真实相机**（透视 + 正交 + 顶视）替换原先手搓的假相机
（假相机没有 `fov`/`top`/`bottom`，会静默让测量失真）。前端 **212/212**。

## 3.37 远景改为"四个等边三角形靶心" + Minecraft 启动脚本（2026-09-24）

### (a) 远景样式：镜头拉远后换成旋转靶心

操作者要求：镜头远离到一定程度后，不再是瞄准框，而是"四个等边三角形组成的靶心围绕着目标旋转"。
经确认：**始终正对镜头、在屏幕平面内自转**。

| 项 | 实现 |
| --- | --- |
| 新对象 | `selection-far-target`（挂在 ring 上，**不继承机体姿态**），内含 1×1 的 quad |
| 图形 | canvas 画 **4 个等边三角形**（0/90/180/270° 均匀分布，尖角朝内、平边朝外）；边长 = 高 × 2/√3 |
| 朝向 | 每帧 `quaternion.copy(camera.quaternion)`，再 `rotateZ(turn)` —— 局部 Z 即视线轴，所以**在屏幕平面内自转** |
| 尺寸 | **视口高度的 16%**（屏幕占比，任何距离都一样大）；quad 是单位方形，按 span 缩放 |
| 自转 | 3.2 s 一圈；`ui-reduced-motion` 下不转 |
| 切换 | 与瞄准框**交叉淡化**，不是硬切：ramp 0.45→0.88（约 **10 m → 16 m**） |

⚠️ **关键坑**：`getSelectionEmphasis()` 返回的是**尺寸倍增器 1.0→3.2**，不是 0→1 的比例 ✗。
最初把淡化阈值写成 0.55/0.95 去比它，结果**任何距离**都判定为"远景"（最小值就是 1.0），
近处也显示靶心、瞄准框 alpha 恒为 0。修法：新增 `getSelectionDistanceRatio(camera)` 返回
**未缓动的 0..1 距离 ramp**，淡化改用它（emphasis 内部也改为复用同一 ramp）。

另外：`NoBlending` 会让 alpha 失效，所以把着色器材质的混合改为 `NormalBlending`（不透明时结果不变），
并新增 `opacity` uniform 供淡化使用。

实测：

```text
距离 | ramp | emphasis | 框 alpha | 靶心占比 | 靶心
  1 m | 0.00 |     1.00 |     1.00 |   0.0% | 隐藏
  8 m | 0.29 |     1.46 |     1.00 |   0.0% | 隐藏
 12 m | 0.53 |     2.20 |     0.91 |  16.0% | 显示   ← 开始交叉
 16 m | 0.76 |     2.89 |     0.18 |  16.0% | 显示
 20 m | 1.00 |     3.20 |     0.00 |  16.0% | 显示   ← 框已完全退场
 30 m | 1.00 |     3.20 |     0.00 |  16.0% | 显示
```

其他验证：靶心法线与相机法线点积 **1.0000**（完全正对）；自转后法线不变（确认在屏幕平面内转）；
4 个三角形全部等边（边长 30.0 = 高 26 × 2/√3）、尖角均朝内、最外半径 48/64 不溢出画布。

**后续微调（操作者反馈）**：①"三角形的尖离得太远" ②"这种模式下 Target 下面的三角就不需要了"。

① 尖角半径 **22 → 12**，同时**外轮廓保持**（48 → 47，三角形因此变高：26 → 35）。
   只把角收进去、不动外缘 —— 外观尺寸不变，改变的正是"尖离中心多远"。
   两个常量：`SELECTION_FAR_TARGET_TIP_RADIUS` / `SELECTION_FAR_TARGET_OUTER_RADIUS`。
② 标签下的小三角（`selection-target-tag-arrow`）在远景模式下**与框同步淡出**（`opacity = 1 − blend`，
   `blend > 0.99` 时 `visible = false`）：它和靶心指向同一架飞机，两个指针就是冗余。
   实测 1–8 m 显示、12 m 0.91、16 m 0.18、≥20 m 隐藏。

### ⚠️ 靶心尺寸的真约束：标签，不是审美（同日再修）

操作者："三角又相对太大了，挡住了 Target 字符"。

**先量后改**，量出来两件事：

**(1) 我此前的测量方法漏了旋转。** 三角形的**底角**比外缘更远：
`corner = √(outer² + (side/2)²)` = √(47² + 20.2²) = **51.2**（画布 px），而我只按 outer 47 算。
靶心每转一圈，底角就会扫到标签上 —— 这正是"挡住 Target"的机制，而且是**周期性**的。

**(2) 符号也搞反过**：屏幕 y 向下增长，`reach` 比标签偏移大 = 重叠。按修正后的判据量，
原有尺寸（tip 22→12 / outer 47 / 屏占比 0.16）在**每个**远景距离上都重叠：
12 m 叠 6.3 px、48 m 叠 22.4 px。

修正（按"最坏旋转相位"定尺寸）：

| 常量 | 改前 | 改后 |
| --- | --- | --- |
| `SELECTION_FAR_TARGET_TIP_RADIUS` | 12 | 12（不变，尖仍靠内） |
| `SELECTION_FAR_TARGET_OUTER_RADIUS` | 47 | **30** |
| `SELECTION_FAR_TARGET_SCREEN_FRACTION` | 0.16 | **0.12** |
| 三角形高 / 角半径 | 35 / 51.2 | **18 / 31.7** |
| `SELECTION_TAG_MAX_SCALE` | 24 | **48** |

最后一个是因为**标签缩放上限本来太紧**：24 对应约 48 m，而 49 m 场地的**对角线是 69 m** ——
越过上限后标签不再保持屏幕尺寸、反而缩小贴向飞机，远景靶心又会碰到它。放宽到 48（约 96 m）。

实测（reach = 最坏角半径投影到屏幕；clearance = 标签底边 − reach，正数=让开）：

```text
距离   | reach px | 标签底边(飞机上方) px | clearance
 12 m |     32.1 |                 62.7 |  30.6 ok
 20 m |     32.1 |                 54.4 |  22.3 ok
 48 m |     32.1 |                 47.2 |  15.0 ok
 69 m |     32.1 |                 45.6 |  13.4 ok   ← 场地对角线
 90 m |     32.1 |                 44.7 |  12.6 ok
```

**测试**：新增「远景靶心绝不允许碰到 TARGET 标签」——用**记录式 canvas 桩**取出真实绘制的
角半径，再逐距离比较标签底边，要求余量 > 5 px；几何用例的"尖靠内"阈值同步改为 ≤ 外径 45%。
前端 **217/217**。

### (b) `scripts/start-minecraft.ps1`

现场重启时游戏是用临时脚本拼装 Fabric 启动命令拉起的，现固化为仓库脚本：

- 合并 **profile + 原版** 的 libraries 与 arguments（profile 是 `inheritsFrom 1.21.1`，两者缺一不可）；
- 按 `rules` 过滤（Windows / x64 / features），解析 maven 坐标到 `libraries\` 路径；
- 带上 `-Dmini_drone.world.metres_per_block=<参数>`、`-Dmini_drone.mocap.enabled=true`；
- **世界名默认取 `saves/` 里最新修改的那个**（现场世界名是中文，写死有编码风险）；
- `-DryRun` 打印完整命令便于排查；`-Console` 用 java.exe 保留控制台；
- 参数逐个**引号包裹**：`Start-Process -ArgumentList` 不做转义，路径里的空格会把参数拆开。

用法：`npm run minecraft:start` 或 `.\scripts\start-minecraft.ps1 -DryRun`。

### (c) ⚠️ 后端 mocap 参数名踩坑（重启环境时踩到）

重启后端时按记忆写了 `--mocap-mode` / `--mocap-profile` / `--mavlink-max-vehicle-count`，
**全部被静默忽略**，后端落在默认的 **real** 模式（源 `10.1.1.22`、health `15151`），
于是动捕信标收不到（`beacon_received: false`）、只发现 1 个槽位。正确名字：

| 错 | 对 |
| --- | --- |
| `--mocap-mode` | `--mocap-source-mode` |
| `--mocap-profile` | `--mocap-source-profile` |
| `--mavlink-max-vehicle-count` | `--mavlink-max-vehicles` |

改正后立刻 3 架飞机全部 `admission=ready / beacon=true / healthy=true`。
**参数名一律从 `backend/src/config/RuntimeConfig.cpp` 核对，不要凭记忆写。**

## 3.38 两种样式都加入场动画（2026-09-24，操作者要求"自行设计"）

原来只有交叉淡化，没有"抵达感"。设计如下：

| 项 | 设计 |
| --- | --- |
| 触发 | **一次性**（0.5 s），不是距离驱动 —— 距离 ramp 是**稳态函数**，由它驱动的任何量都会停在相机停下的地方，只会变成"随缩放变化的大小"，不是动画 |
| 靶心入场 | 从**0.55 倍**绽开到 1.0（ease-out cubic）：四个三角形由内向外"锁定"到飞机上 |
| 框入场 | 两条边从**张开 0.16**（sprite 单位）夹紧到静止位：框"咬合"到飞机上 |
| 淡化关系 | 两个入场都是**几何的**，不透明度仍是纯交叉淡化 —— 淡到一半就是淡到一半，与入场无关（测试因此不受影响） |
| 抖动抑制 | **双阈值**（进入 0.60 / 退出 0.40）：停在边界附近不会每帧翻转、反复重播 |
| 首帧 | 只记录当前样式、**不播动画** —— 否则"选中一架飞机"也会播一次入场，而那不是"变化" |
| 过冲 | 两者都**朝稳态值收敛、绝不越过**（所以是"由小变大"/"由宽变窄"）。若反过来（先变大）会让新样式在入场途中扫过邻居 —— 包括飞机上方的 TARGET 标签 |

实测（一次离开、一次返回）：

```text
靶心入场：t=+0 ms 系数 0.55 | +100 ms 0.77 | +200 ms 0.90 | +300 ms 0.97 | +400 ms 1.00
框  入场：张开 0.160 -> 0.035 -> 0.000（随后只剩呼吸的 ±0.207 摆动）
```

**测试**：新增「两种样式各自在抵达时播入场，且只在这时播」——断言近处静止时无动画、
离开时靶心变小且框仍张开、各自在时长内收敛、以及**在边界附近抖动不会重播**（`startedAtMs` 不变）。
另有 2 项既有用例因入场是计时的，改为**推进模拟时钟**后再读稳定值。
前端 **218/218**。

## 3.39 切换点拉近（2026-09-24，操作者："靶心切换的标准太远了"）

| 常量 | 改前 | 改后 | 对应距离 |
| --- | --- | --- | --- |
| `SELECTION_FAR_STYLE_FADE_START` | 0.45 | **0.25** | 10.7 m → **7.3 m** |
| `SELECTION_FAR_STYLE_FADE_END` | 0.88 | **0.62** | 18.0 m → **13.5 m** |
| `SELECTION_ENTRANCE_FAR_ENTER` | 0.60 | **0.42** | 13.2 m → **10.1 m** |
| `SELECTION_ENTRANCE_FAR_EXIT` | 0.40 | **0.24** | 9.8 m → **7.1 m** |

（距离 = 3 m + ramp × 17 m，即 `SELECTION_EMPHASIS_NEAR_M` 到 `_FAR_M`）

实测（入场已收敛）：

```text
距离   | ramp | 框 alpha | 靶心
  7 m | 0.24 |     1.00 | 隐藏
  9 m | 0.35 |     0.81 | 显示   ← 开始交叉
 11 m | 0.47 |     0.36 | 显示
 13 m | 0.59 |     0.02 | 显示   ← 框基本退场
 15 m | 0.71 |     0.00 | 显示
```

标签余量随之前移而**更大**（靶心提前出现时标签位置更高）：8 m 处约 38 px，48 m 处 15 px。
标签余量测试的距离列表同步扩到 **8 m 起**（含 8/10/12），并加入时钟推进以量取收敛尺寸。
前端 **218/218**。

## 3.40 取消交叉淡化：改为"交接 + 入场"（2026-09-24，操作者："会卡一下"）

操作者："靶心还是有点远；三角的入场不够干脆，渐显可以取消，会卡一下，可能是缩放和动画有冲突。"

**根因（确认了操作者的猜测）**：靶心走的是**两个不同时钟**——

| 量 | 时钟 | 在 ramp 0.25–0.42 之间 |
| --- | --- | --- |
| 不透明度（渐显） | 距离 ramp | 已可见，**缩放是稳态值 1.0** |
| 缩放（入场） | 计时器（ramp 0.42 触发） | 触发瞬间**从 1.0 跳到 0.55** |

即：靶心在入场开始前就已经（半透明地）以**稳态尺寸**出现，入场一触发缩放就往下跳 —— 那一下就是"卡"。

**改法：取消渐显，改为硬交接**。谁在屏幕上完全由**样式**决定，入场只负责让"刚到的那一个"有抵达动作：

- `isFarSelectionStyle()` 统一裁决：近处→框可见、靶心隐藏；远处→反之；标签下的小三角同进退；
- 不再需要淡化 ⇒ 撤掉为它引入的 `opacity` uniform、`NormalBlending`（恢复 `NoBlending`）等一整套死机制；
- 入场时长 0.5 s → **0.28 s**（更干脆）。

实测（每个距离都已收敛入场）：

```text
距离   | ramp | 样式 | 框     | 靶心   | 标签下三角
  6 m | 0.18 | near | 显示   | 隐藏   | 显示
  7 m | 0.24 | near | 显示   | 隐藏   | 显示
  8 m | 0.29 | far  | 隐藏   | 显示   | 隐藏   ← 交接点（ramp 0.28）
 12 m | 0.53 | far  | 隐藏   | 显示   | 隐藏
```

交接帧实测：`样式 far | 靶心可见 true | 入场系数 0.55 | 框可见 false` —— 出现与入场**同帧开始**，
不再有跳变。入场曲线：`0.55 →(80ms) 0.84 →(160ms) 0.96 →(320ms) 1.00`。

**切换点也再次拉近**：`ENTRANCE_FAR_ENTER` 0.42 → **0.28**（10.1 m → **7.8 m**），
`EXIT` 0.24 → **0.16**（7.1 m → 5.7 m）。原来的 `FADE_START/END` 随淡化一起删除。

**测试**：改写 2 项用例为**可见性 + 样式**判据（原读 `opacity`），并新增一条不变量——
**任意距离上恰好只有一种样式可见**（每 0.25 m 扫描 1–30 m），这正是"不再有半透明重叠"的机器化表述。
前端 **218/218**。

## 3.41 三角放大一倍 + 入场改为"外围聚拢起旋"（2026-09-24）

操作者："三角相对又太小了，放大一倍应该差不多"；"入场我想要四个三角从比较外围快速聚拢开始旋转"。

### (a) 放大：往**外**长，不往里长

关键判断——**往哪边放大**。上一轮把尖收到 12、外径收到 30 是为了躲开 TARGET 标签；
若直接整体缩放，标签必被挡。所以按"**高度翻倍、内孔不变**"做：

| 量 | 改前 | 改后 |
| --- | --- | --- |
| 尖半径 | 12 | **12**（不变 —— 内孔继续框住飞机，不会盖住它） |
| 外径 | 30 | **48** |
| 三角形高 | 18 | **36**（正好 2×） |
| 角半径 | 31.7 | 52.3 |

### (b) 入场：从 1.35 倍外圈**聚拢**，并带旋转提前量

| 量 | 改前 | 改后 |
| --- | --- | --- |
| 起始缩放 | 0.55（由小长大） | **1.35**（由外收拢） |
| 入场时长 | 0.28 s | **0.3 s** |
| 旋转 | 全程匀速 | 起始**落后 1.35 rad**，在入场中卸掉 ⇒ 快速转进、再稳住慢转 |

实测（尺寸与自转速率同时收敛）：

```text
t(ms) | 尺寸 vs 收敛后 | 自转(deg/s)
    0 |          135% |     0
   40 |          123% |   787    ← 快速旋转地聚拢
  120 |          108% |   457
  200 |          101% |   237
  280 |          100% |   148
  780 |          100% |   113    ← 稳定慢转（360°/3.2 s）
```

### (c) 代价：标签必须抬高（`SELECTION_TAG_RISE_FRACTION` 0.25 → **0.46**）

三角变大 + 入场最宽时达 1.35 倍，两者都让外缘更高。实测余量：

| 时刻 | 余量范围（8–69 m） |
| --- | --- |
| 收敛后 | 26.9 – 46.7 px |
| **入场最宽时** | **8.3 – 28.1 px** ✓ |

即：为了让"更大 + 从更外围聚拢"成立，标签在远处须抬高（24 m 处由约 52 px 变为约 85 px）。
抬得太高会像"标签脱离飞机"（§3.35 已否掉 1.0 档的 153 px），0.46 是"够让开"与"不脱离"之间的取值。

**测试**：标签余量用例改为**同时校验收敛后与入场最宽两个时刻**（后者才是真正的约束）；
入场用例断言方向反转为**单调收拢**（`135% → 100%`，且过程中不回弹）、以及起始必须 ≥1.35。
前端 **218/218**。

## 3.42 三角尺寸回调 + 让入场"有过程"（2026-09-24）

操作者："又太大了，而且现在还是直接到位，没有过程"。

### (a) 尺寸 36 → 27（回到两版之间）

| 量 | 太小（被否） | 太大（被否） | **现在** |
| --- | --- | --- | --- |
| 三角形高 | 18 | 36 | **27** |
| 外径 | 30 | 48 | **39** |
| 尖半径 | 12 | 12 | **12**（内孔始终框住飞机） |

### (b) 为什么"没有过程" —— 三个原因叠加

上一版参数：起点 **1.35**（行程仅 35%）、时长 **0.3 s**、缓动 **ease-out cubic**。
而 cubic 在 `t=0.2` 就走完 **49%**、`t=0.33` 走完 **70%** —— 也就是 **100 ms 内动作基本结束** ✗。
**行程小 × 时间短 × 曲线前倾**，三者叠加就等于"直接到位"。

| 项 | 改前 | 改后 |
| --- | --- | --- |
| 起点缩放（行程） | 1.35（35%） | **1.6（60%）** |
| 时长 | 0.3 s | **0.5 s** |
| 缓动 | ease-out cubic | **ease-out quad**（`1−(1−t)²`，不那么前倾） |

实测（过程可见，且尺寸与转速同步收敛）：

```text
t(ms) | 尺寸 | 已走完 | 自转(deg/s)
    0 | 160% |     0% |     0
   50 | 149% |    19% |   406
  100 | 138% |    36% |   375
  150 | 129% |    51% |   345
  250 | 115% |    75% |   283
  500 | 100% |   100% |   159
  800 | 100% |   100% |   112   ← 稳态慢转
```

关键区别：**半程（150 ms）时只走到 51%**，而不是旧版 100 ms 就走完 49% 再迅速收尾。

### (c) 标签余量（含入场最宽时刻）

```text
距离   | 收敛后余量 | 入场最宽余量
  8 m |   57.1 px |   31.6 px
 24 m |   42.2 px |   16.7 px
 69 m |   37.3 px |   11.8 px   ← 最紧
```

因为三角回到 27，`RISE_FRACTION` 保持 0.46 即可，无需再抬标签。

**测试**：入场用例改为**在入场期间多点采样**，断言①单调收拢②**半程时必须处于行程中段**（
`1.05 < scale < 1.55`，这条正是"要有过程"的机器化表述）③最终收敛到 1。
标签余量用例同步为 1.6 倍起点。前端 **218/218**。

## 3.43 三角再小一档 + 切换再拉近（2026-09-24）

操作者："三角又太大了，切换的距离还是太远了，暂时别管入场动作"。

| 项 | 改前 | 改后 | 说明 |
| --- | --- | --- | --- |
| 三角形高 | 27 | **21** | 18 被否（太小）、27 被否（太大）⇒ 往 18 靠 |
| 外径 | 39 | **33** | 尖半径仍 **12**，内孔不变 |
| `ENTRANCE_FAR_ENTER` | 0.28 | **0.16** | 7.8 m → **5.7 m** |
| `ENTRANCE_FAR_EXIT` | 0.16 | **0.08** | 5.7 m → **4.4 m** |

屏幕上的实际尺寸（1080p、屏占比 0.12）：三角 **21 px** 高、整环 **67 px** 宽、内孔 **24 px** 宽。

切换点迭代记录：13.2 m → 10.1 m → 7.8 m → **5.7 m**。

标签余量仍充裕（三角变小所致）：收敛后 44–72 px、入场最宽时 **23–50 px**。

**测试**：切换区间变化 ⇒ 3 个用例的距离列表同步前移（近距改 1–5 m、远距改 7 m 起、
标签余量用例从 6 m 起扫描）。前端 **218/218**。

## 3.44 修复"聚焦"第二次起不平滑（2026-09-24，操作者反馈）

操作者："本来设计切换聚焦目标会有一个平滑的镜头转移，但是现在除了第一次聚焦之后，
都变成等一下然后直接跳到下一个聚焦对象了"。

### 根因：跟随逻辑每帧把 target 拉回**旧**的聚焦对象

`sceneManager.render()` **每帧**调用 `viewManager.update()`，而 `update()` 里有：

```js
if (this.focusedObject) {
    this.controls.target.copy(focusedObject.position);   // 钉住当前聚焦对象
}
```

而 `focusedObject` **只在动画结束的那一帧**才由 `_pendingFocusObject` 赋值。
于是**第二次**聚焦时，同一个 `controls.target` 被两个东西抢：

| 谁 | 想把它设成 |
| --- | --- |
| 动画（rAF，每帧） | 从 A 平滑插值到 B |
| `update()`（渲染循环，每帧） | **A**（旧的聚焦对象） |

结果：动画被每帧覆盖 ⇒ target 停在 A（看起来"等一下"）；直到动画 `t >= 1` 那一帧才整体
设为 B 并把 `focusedObject` 换成 B ⇒ **跳过去**。

**第一次为什么正常**：此时 `focusedObject` 还是 `null`，`update()` 不进入那个分支，动画无人干扰 ✓
—— 与操作者描述的"只有第一次正常"完全一致。

### 修复

一行守卫：动画进行中，跟随逻辑让位。

```js
if (this.focusedObject && this._animFrameId === null) { ... }
```

`_animFrameId` 在动画期间非 null、结束帧置 null，正好是"是否正在交接"的判据。

### 验证

新增 `tests/view-focus-handover.test.mjs`（3 项）：

1. 动画进行中 `update()` 不得改写 target；
2. 动画结束后跟随逻辑仍然生效（不破坏原有跟随功能）；
3. **端到端**：用桩驱动 rAF 逐帧推进，重现"聚焦 A → 聚焦 B"，断言 target 是**多帧渐进**跨过去
   （中途帧占比 `>0.05 且 <0.95` 的至少 3 帧）、且全程单调不回退。

**并做了反向验证**：把修复临时撤掉后，第 1、3 项**如期失败**（第 3 项正是"没有平滑过程"的机器化表述），
确认测试真的能抓住这个 bug，而不是碰巧通过。前端 **221/221**。

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
- ⚠️ **8080 上可能同时存在两个后端**（本次踩到，浪费了一个小时）：先杀后端再启动自己的实例时，
  Electron 的 supervisor（`backend-supervisor.js`，启动时 `probePort` 发现没人监听就会自己 spawn 一个）
  会在它之后拉起**一个无参数后端**（`backend\build\drone_backend.exe`，没有 `--mavlink-endpoint`）。
  Windows 的 `SO_REUSEADDR` 允许**两个进程绑同一个 UDP/TCP 端口**，于是 8080 上的连接被随机分给
  两个后端：`probe_mocap_source` 返回的是 A 的状态，`connect_mocap_source` 落到 B（B 连适配器都没有，
  回 `adapter.ready: none` 然后断开 WS）。**排查第一步先确认只有一个后端**：

  ```powershell
  Get-CimInstance Win32_Process -Filter "Name='drone_backend.exe'" | Select-Object ProcessId,CreationDate,CommandLine
  Get-NetTCPConnection -State Listen -LocalPort 8080 | Select-Object OwningProcess
  Get-NetUDPEndpoint | Where-Object LocalPort -in 15151,14561,18151 | Select-Object LocalPort,OwningProcess
  ```
  这次的做法：杀掉 supervisor 那个无参数实例（它不会自动重启），保证只有自己的实例持有 8080。
- **后端 stdout 是块缓冲**：重定向到文件时最后几 KB 会滞后（`[adapter:...]` 行看起来"卡住"）。
  用 WS 现场读状态（`scripts/mocap-source-live-check.mjs`）比看日志更可靠。
- ⚠️ **Release 目录残留的 .obj 会做出"启动即崩"的假象**：`backend/build-release` 里旧对象文件与
  新头文件混编时，进程会以 `0xC0000409` 秒退且**没有任何输出**（stdout 缓冲丢失）。
  遇到就用 `cmake --build backend/build-release --clean-first` 重建，不要先怀疑自己的改动。

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

# 2b) 一条命令跑完双向验收（对运行中的后端，读链路归属 + 飞机列表 + 断言）
node scripts/mocap-source-live-check.mjs --order=virtual,real

# 3) 飞机列表来自 telemetry_frame 的 payload.drones[].drone_id
# 4) 飞行验收清单（悬停/阶跃/顶速/擦碰/硬撞，缺障碍物时如实报 SKIP）
node scripts/course-check.mjs --north        # 在模组仓库
```

## 6. 第二架无人机怎么出现（已确认）

操作者已确认：**沿用「无人机放置器」右键方块 = 在该处新增一架并分配 id**（不新增专用生成器物品）。
已按此实现（§3.5）：右键方块新增，右键**某架**收回那一架；`/minidrone drone place <pos>` 与物品同一路径。

## 7. 未决/未证实（不要当成结论）

- 后端日志里 `mavlink.peer.rejected_packet`（非法 MAVLink 帧）的来源：15:15:47–15:18:56 有一段洪流，
  与 `link.timeout`(15:18:03) / `link.restored`(15:18:28) 前后相邻；**日志不记发送方 IP**，未定位。
- 前端无人机列表里一度出现第二个真实身份 `real_drone_001_001_ip1`（其后消失）。**未查证**是否真实第二台设备，
  也可能是过期绑定被 `--mavlink-peer-timeout-ms=10000` 回收。
