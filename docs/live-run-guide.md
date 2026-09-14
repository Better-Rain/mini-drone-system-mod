# 联调手册：让主项目连上 Minecraft 里的虚拟动捕与虚拟飞机

本文是**人**的操作步骤：从零到一个能收发的联调环境。契约细节（端口、字段、准入判据）在
[`main-backend-compatibility.md`](main-backend-compatibility.md)，两边的分工背景在主项目的
`docs/minecraft-mod-next-steps-prompt.md`。

## 0. 前置

| 项 | 要求 |
| --- | --- |
| 主项目 | `C:\Users\VLT_BR\Projects\mini-drone-system`，需已构建 `backend\build\drone_backend.exe` |
| 本项目 | `C:\Users\VLT_BR\Projects\mini-drone-system-mod` |
| Java | 21（构建与开发客户端） |
| Node | 22+（只有验证脚本需要，用到内置 `WebSocket`） |
| 端口 | `18082`（隔离 WS）、`14561`（backend MAVLink）、`14601`（模组本地）、`18151`/`18152`（动捕健康/控制）必须空闲 |

不要用生产 backend 端口（`8080`）做这件事：隔离实例与现场参数必须分开，现场实例接的是真机和真实动捕。

## 1. 构建并安装模组

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot'
.\gradlew.bat build
.\scripts\install-pcl-instance.ps1
```

`install-pcl-instance.ps1` 只替换该 PCL 实例里匹配 `mini-drone-system-mod-*.jar` 的旧版本。
**更新模组前先完全退出 Minecraft**，否则 JAR 被占用。

## 2. 启动顺序

顺序有实际意义：先让 backend 绑定好端口，模组再开始发心跳。

### 2.1 启动隔离 backend

```powershell
.\scripts\start-isolated-backend.ps1
```

脚本会先检查端口占用（占用即停止，不会杀掉已有进程），然后让主项目的 launcher 启动
`backend\build\drone_backend.exe`，参数为虚拟 profile、`expected_drone_id=minecraft_drone_01`、
`udpin://127.0.0.1:14561`。保持这个终端开着。

### 2.2 启动 Minecraft

开发客户端：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot'
.\gradlew.bat runClient
```

或从 PCL 启动 `Mini Drone System 1.21.1` 实例（该实例要求 Java 21）。

### 2.3 进入世界后打开虚拟动捕源

```text
/minidrone mocap enable
/minidrone link status
```

`mocap enable` 按世界保存，重新进入该世界后仍然有效。它做两件事：向 `18151` 周期性发健康信标
（20 Hz），并在 `18152` 起控制端点。`/minidrone mocap status` 返回的 `[ENABLE]`/`[DISABLE]`
按钮可以直接点击。

`link status` 里要能看到：

```text
state=CONNECTED, remote=127.0.0.1:14561, local=127.0.0.1:14601,
backend_fresh=true, ..., mocap_health=true, mocap_control=listening:127.0.0.1:18152,
mocap_expected_id=minecraft_drone_01, mocap_forwarding=forwarding
```

若 `backend_fresh=false`：backend 没起来，或 `remote` 指错了端口。若 `mocap_control=not-listening`：
`18152` 被别的进程占用。

### 2.4 前端连到隔离 backend

主项目前端（或打包后的桌面客户端）用查询参数指向隔离端口：

```text
?backendWs=ws://127.0.0.1:18082
```

### 2.5 选中动捕源

在"设置 → 动捕源"里让候选 `minecraft_virtual_mocap` 变成**已连接**（点候选卡片的"连接"，或先
"重新扫描"再连接）。发现流程就是向 `127.0.0.1:18152` 发 `VLT_RELAY_STATUS_V1` 并校验
`schema`/`ok=true`，所以模组必须先 `mocap enable`。

这一步是**必需**的，但原因容易被误解：

- 健康信标本身是挂在 MAVLink adapter 的监听端口上的，不选中源也会被消费，位置命令照样能过门禁；
- 但"断开链路 → 后端请求暂停转发"只在有**活动动捕源**时才发（否则回执是
  `no_active_mocap_source`），前端显示的源状态也才有意义。

### 2.6 确认飞机

模组的 MAVLink 端点是隔离 backend **启动时静态配置**的（`udpin://127.0.0.1:14561`），所以 slot
`minecraft_drone_01` 一直属于这个 adapter，心跳一到就绑定，前端的"可用无人机"里**不会**出现候选卡片——
那个列表是局域网扫描的结果，只探测私有网段并排除回环地址（见主项目 `tools/mavlink-discovery.js`）。

按这两处确认，而不是等候选出现：

- 模组侧 `/minidrone link status` 的 `state=CONNECTED`、`backend_fresh=true`、收发计数在增长；
- 前端的**位置转发**一格从"等待"变成就绪（它显示的是 backend 实际执行的准入判据和实测值）。

若曾在前端断开过这条链路，点"连接"重新接上；重新接上后又会回到前 ~6 秒的等待期（见 §3）。

## 3. 每一阶段应该看到什么

| 阶段 | 期望 |
| --- | --- |
| backend 起来 | 日志 `mavlink.transport.ready`、`mavlink.mocap_health.listener_ready` |
| 模组发心跳后 | `mavlink.heartbeat.detected`、`mavlink.binding.established`（slot `minecraft_drone_01`） |
| 选中动捕源 | `source_connected`，前端候选显示在线 |
| 位置转发一格 | 先是"等待"，然后"就绪"（就绪后位置命令与起飞才被放行） |
| 下发位置/PVA 命令 | 飞控收到 MAVLink 消息 84，虚拟飞机沿限速轨迹移动 |
| 点「起飞」 | backend 自动走 请求 indoor origin → GUIDED → ARM → NAV_TAKEOFF，虚拟飞机以 0.8 m/s 爬升 |

**建链后的前几秒命令会被拒，这是设计如此**：backend 按 300 ms 间隔排空一份 69 项的参数清单，
用车辆回传的 `EK3_SRC1_*` 判定"飞控在融合外部导航"。在此之前位置命令返回
`external_nav_horizontal_fusion_unstable`，起飞还会额外因为缺 `EK3_SRC1_POSZ`/`YAW` 返回
`takeoff_preflight_unstable`。实测清单补齐约 3–4 秒（视链路而定）。看到这些状态先等十秒再判断，
不要当成链路故障（见兼容性文档 §6.2）。

## 4. 不用 Minecraft 也能验证这一整套

契约的模组侧可以单独验证——脚本扮演虚拟飞机和虚拟动捕源，跑在与模组完全相同的端口和节奏上，
并按操作员的顺序走完整个会话：

```powershell
# backend 已按 §2.1 启动的前提下
node .\scripts\verify-contract.mjs
node .\scripts\verify-contract.mjs --move-mps=1.4      # 加上"飞行中"的一致性检查
```

14 项检查：发现流程把虚拟源列为可用候选、控制端点应答、健康信标被接受、
参数清单补齐（`EK3_SRC1_POSXY/POSZ/YAW`）、`set_flight_mode` 到达、
起飞序列（请求 indoor origin → 解锁 → 起飞 → 爬升）、`set_pva_target` 被放行并变成消息 84、
帧内容与请求一致、飞行中交叉一致性在窗口内、电量与姿态进入发布给前端的机型状态、
NED→世界坐标映射与文档一致、降落并上锁、断开触发转发保持、重连触发恢复。全过返回 0，任一失败返回 1
并列出失败项；摘要里还带 backend 的最后一条命令回执，用于定位卡在哪一阶段。

它占用模组的那几个端口（本地 `14601`、控制 `18152`，并向 `18151` 发信标），所以**验证脚本与正在运行的
模组不能并存**：先退出 Minecraft 再跑。backend 只认它学到的那个来源端点，`--local-port=0` 只能避免端口
占用报错，不能让两者同时被 backend 接受。

`--beacon-ms=250 --move-mps=1.4` 会复现"信标太慢"这一类故障（实测偏差 0.2408 m，超过 0.10 m 限值），
可以用来确认这个检查本身是有效的。

## 5. 故障对照

| 现象 | 先查 |
| --- | --- |
| 前端候选"未响应" | 世界内是否执行过 `/minidrone mocap enable`；`18152` 是否被占用 |
| 候选在线但前端长期没有健康状态 | 两侧 `expected_drone_id` 是否逐字一致（兼容性文档 §2.1） |
| 命令一直 `session_pending` | 模组这次用了动态本地端口（`14601` 被占用触发了回退）：在前端断开再连接飞机，见 §2.0 |
| 位置命令一直 `external_nav_horizontal_fusion_unstable` | 刚建链的前几秒属正常；持续则看参数清单是否完成（§3） |
| 一飞起来位置命令就被拒 | 信标周期 × 水平限速超过 0.10 m 一致性窗口（§5），确认模组按 50 ms 发信标 |
| 起飞被拒 `takeoff_preflight_unstable` | 参数清单还没补齐。起飞比位置命令要求更多：除 `EK3_SRC1_POSXY` 外还要 `POSZ` 与 `YAW`，回执里会列出 `estimator_position_z_source_missing` / `estimator_yaw_source_missing` / `external_nav_fusion_unconfirmed`。实测补齐约 3–4 秒，等前端的"位置转发"一格就绪再点起飞 |
| 点了「解锁」再点「起飞」后卡在 `awaiting_origin` | 后端的起飞序列要求飞行器在确认 Home/Global Origin 之前保持**未解锁**（`awaiting_origin` 阶段发现已解锁会以 `origin_setup_armed_unexpectedly` 失败）。直接用「起飞」一次走完 GUIDED→ARM→TAKEOFF 即可 |
| 断开链路时 `mocap.forwarding_hold_failed` | 模组没有应答 `VLT_RELAY_HOLD_FORWARDING_V1`（§4.1） |
| 虚拟飞机对 PVA 没反应 | 该帧 `type_mask` 是否是"全忽略"；模组逐位解析，见 §6.1 |
| 日志出现 `Error loading saved data: mini_drone_virtual_mocap_settings`（或 `..._training_arena`） | 用的是修复前的 JAR：`mocap enable` 会在重进世界后失效、训练场登记会在重进后丢失（方块还在，但 `arena clear` 清不掉）。重新构建并安装模组；磁盘上的旧存档能被修复后的版本正常读回 |

## 6. 关停

退出世界（模组会停止 MAVLink 与控制端点），然后停掉隔离 backend 终端。`14601`、`18151`、`18152`
会在模组停止后释放；若残留，用 `Get-NetUDPEndpoint -LocalPort 14601` 找占用进程。
