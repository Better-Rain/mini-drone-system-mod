# 对主项目的需求：动捕源「虚拟 / 现实」可切换

> 用途：把本文交给主项目那侧的对话，实现完后由模组侧（本项目）按 §5 验收。
> 结论先行：**现在"在前端选中虚拟源"只改了源管理层的选择，不改命令门禁、也不改飞机链路**，
> 所以用户会看到"源在线但没有飞机"。需求就是把"选中即生效"补齐。

## 1. 背景

- 模组（本项目）提供两样东西：**虚拟动捕源**（控制 `127.0.0.1:18152`、健康 `127.0.0.1:18151`）与
  **虚拟飞控**（MAVLink v1，system 54 / component 1，固定本地端口 `127.0.0.1:14601`，
  健康信标广告 `expected_drone_id: "minecraft_drone_01"`）。
- 同一台主机上跑**一个**后端是设计目标（不要为了模组长期并存第二个实例）。
  现场实例的配置是：relay 健康/控制 `15151`/`15152`、真机端点 `udpout://10.1.1.54:14550`。
- 模组从不运行后端，只是 UDP 对端。

## 2. 实测现状（证据）

| # | 事实 | 证据 |
| --- | --- | --- |
| 1 | 源的**发现与选择**已经可用：内置 `minecraft_virtual_mocap` 候选（source `127.0.0.1:15150`、health `18151`、control `18152`），现场后端上实测 `available: true`、`probe_message: "virtual motion-capture source is online"`；`connect_mocap_source` 能把它设为 `active_mocap_source_` | 对 `ws://127.0.0.1:8080` 发 `discover_mocap_sources` 的实际返回；`DroneServerControl.inc` |
| 2 | **命令门禁不跟随所选源**：适配器用自己那份 `config_.mocap`（`--mavlink-mocap-health-host/port`、`expected_drone_id`）订阅共享健康监听，并据此做准入与起飞前判据 | `MavlinkVehicleAdapterMocapRecovery.inc#ensure_mocap_health_listener_started`、`MavlinkVehicleAdapterBinding.inc#evaluate_command_admission_locked`、`MavlinkVehicleAdapterTakeoffSafety.inc` |
| 3 | **运行期无法改这份配置**：`set_runtime_options` 只切开发者开关 | `MavlinkVehicleAdapterDispatch.inc:13` |
| 4 | **飞机链路也不跟随**：端点由 `--mavlink-endpoint(s)` 决定；现场是 `0.0.0.0:14550` 或真机 IP，模组在回环上，两者不相遇 | `RuntimeConfig` / 现场进程命令行 |
| 5 | 于是症状是：前端里源显示**在线**，但看不到飞机、命令无效（现场实测） | 同上 |
| 6 | 打包版也不行：`buildBackendArgs` 在 `real`/`mocap` 下**写死** `--mocap-source-mode=real --mocap-source-profile=real_mocap`（端口取自 `backend-config.json`），没有 virtual 分支；`mode: "mock"` 走 `--adapter=mock`，与本需求无关 | `backend-supervisor.js` |
| 7 | 但把配置指对**已经够跑通**：`mocap.healthPort=18151`、`controlPort=18152`、`drone.endpoint=udpout://127.0.0.1:14601`、`systemId=54`、`bindId=minecraft_drone_01` 时，后端能完整驱动虚拟飞机（模组侧 16 项契约检查全过） | 本轮实测 |

## 3. 需求：切换后"选中的源"必须真正生效

用户在前端把动捕源在 real 与 `minecraft_virtual_mocap` 之间切换时，后端需要具备：

- **R1 门禁跟随**：该源的健康端点与 `expected_drone_id` 成为命令准入判据；切换后**旧源的判据必须立即失效**
  （清空/作废旧源的 `last_mocap_health_`），不允许出现"已经切了源、门禁还在用旧信标通过"。
- **R2 飞机链路跟随**：能连到该源对应的飞行器。虚拟源的合理做法：源选中时自动添加
  `udpout://127.0.0.1:14601`（模组固定本地端口，正是为可配置而固定），或至少允许 UI 手工添加回环端点
  （当前局域网扫描不覆盖回环，前端没有入口）。
- **R3 状态可见**：`adapter.status` 能读出**当前生效**的健康端点、`expected_drone_id`、源类型，
  并区分"已选源"与"生效门禁"（现在只有前者完整）。
- **R4 反向切换**：切回 real 时同样清空虚拟源状态，恢复到 relay `15151`/`15152` 与真机端点。
- **R5 打包版无需命令行参数**：`backend-config.json`（或应用设置）里能选 virtual/real，
  supervisor 生成正确参数；虚拟源在前端显示为 Minecraft 虚拟源，而不是顶着 `real_mocap` 的名字。

## 4. 切换语义与安全约束（必须明确写出来）

1. **切换时机**：建议只在相关飞行器**落地且上锁（`armed=false`）**时允许，等价于
   `disconnect_mavlink_drone` 已有的 `MavlinkDisconnectSafety` 门禁；飞行中/已解锁时**拒绝**并给明确状态码。
2. **单源约束保留**：一个后端实例同一时刻只消费一个动捕源。**不要**用"多绑一个监听端口"来实现切换。
3. **不得跨机门禁**：切换后，不同 `expected_drone_id` 的链路必须各自只被自己那份信标门禁；
   真机与虚拟并存的正解是**按 slot / `expected_drone_id` 路由健康信标**，而不是共用同一端口。
   这一条是安全要求：虚拟源的位姿绝不能成为真机命令的准入依据。
4. 切换必须发事件、可审计（带前后生效值）。

## 5. 验收标准（模组侧会按这些测）

- **A1** 启动后 `discover_mocap_sources` 列出 `minecraft_virtual_mocap` 且 `available=true`。（已满足）
- **A2** 选中虚拟源后，**不重启后端、不改启动参数**：`adapter.status` 的生效健康端点=`18151`、
  expected id=`minecraft_drone_01`，并且能绑定虚拟飞机、收到它的遥测（从 `14561` 或 `14601`）。
- **A3** 选中虚拟源后，`set_pva_target` / `takeoff` 能通过准入（不再报 `mocap_health_missing`、
  `external_nav_horizontal_fusion_unstable` 这类来自旧源的判据）。
- **A4** 切回 real 后，虚拟源信标不再影响任何判据（旧状态已清），真机链路恢复原状。
- **A5** 已解锁或飞行中尝试切换 → 被拒绝、状态明确，且虚拟/真机链路状态均不变。
- **A6** 打包版改一个字段即可完成切换（`mode: "virtual"` 或等价），前端源名正确。

模组侧的测试手段：`scripts/verify-contract.mjs`（16 项契约检查，支持后端 `udpin` 与
后端主动 `udpout` 两种链路方向）+ 现场真机联调。本文交付后我会按 A2–A5 加一个"切源中途切换"的用例。

## 6. 我测试时需要的钩子（请实现后告知）

| 需要的 | 说明 |
| --- | --- |
| 切换命令 | 沿用 `connect_mocap_source` 即可，但要求它**同时**更新门禁与链路；若拆成两条命令，请写明调用顺序 |
| 事件 | 切换成功/失败各一条，带 `source_mode`、`effective_health_endpoint`、`expected_drone_id`、`previous_*` |
| 状态字段 | `adapter.status` 里暴露**生效**门的那些值（字段路径按你们的命名，我在脚本里读） |
| 拒绝状态码 | "切换被安全门禁拒绝"的状态字符串 |

## 7. 模组侧保证（不必迁就模组）

- 控制端点 `18152` 应答 `STATUS`/`RECONNECT`/`HOLD`/`RESUME`；健康信标发往 `18151`，schema
  `mocap_relay_health_v1`，`expected_drone_id` 为**字符串** `minecraft_drone_01`（可配）；
- MAVLink v1，system 54 / component 1，**固定**本地端口 `14601`（可配，固定是为了让 `udpout` 可配置）；
- 两种方向都支持：模组发往 `14561`，或后端主动连 `14601`、模组回复来源；
- 帧长一律 MAVLink 1 的 `MIN_LEN`；健康信标 20 Hz（与位置遥测同拍，保证"估计器 vs 动捕"交叉判据不被相位差打穿）。

## 8. 建议的最小实现路径

1. **R5（纯 supervisor/config，约 10 行）**：给 `buildBackendArgs` 加 `virtual` 分支，照抄那组
   `--mocap-source-mode=virtual …`，并让 `backend-config.json` 能选它。**打包用户从此只需一个字段。**
2. **R1 + R3（C++）**：把适配器的健康订阅与 `expected_drone_id` 改为**由选中的源驱动**；
   切换时作废旧 `last_mocap_health_`、重建订阅；`adapter.status` 暴露生效值。
3. **R2**：源选中时自动添加回环端点，或给前端一个手工添加端点的入口。
4. **可选、也是最终形态**：按 `expected_drone_id` 为每个 slot 路由信标，real 与 virtual 可安全并存，
   切换语义自然消失。
