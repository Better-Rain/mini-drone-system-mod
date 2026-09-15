# 主项目后端兼容性参考

本文面向 Minecraft 模组开发和联调。目标是让本模组兼容
`mini-drone-system` 的 Electron 前端和唯一 C++ backend。

当前主项目已经实现 Minecraft 虚拟动捕源的 profile、发现和连接流程。模组侧需要实现并正确启动下面的接口；不需要创建第二个 backend，也不需要让前端直接连接 Minecraft。

## 1. 目标架构

```text
Minecraft Fabric 模组
  |-- MAVLink v1 UDP 遥测/命令 <-> C++ backend
  |-- 动捕健康信标 UDP -------> C++ backend
  `-- 动捕源控制 UDP <-------- C++ backend 的只读探测

Electron 前端 <-> WebSocket <-> 同一个 C++ backend
```

动捕源和无人机链路是两个独立生命周期：

- 动捕源发现只探测控制端点，不等待 MAVLink 心跳。
- MAVLink 链路负责虚拟飞控状态、命令和遥测。
- 动捕健康信标负责告诉 backend 当前源是否健康、是否锁存以及融合契约。
- 控制端口的在线状态不代表无人机已经连接；反过来也一样。

### 1.1 三个必须知道的前提

**主项目同一时刻只持有一个动捕源。** backend 里的 `active_mocap_source_` 是单选，健康/控制监听也只有一组端口：
真实 relay（健康 `15151`／控制 `15152`）与虚拟源（`18151`／`18152`）**不可能被同一个 backend 实例同时消费**。
两者要并存，只能给模组另起一个隔离实例（就是本文默认端口组那套）。这是主项目的设计，不是模组的限制；
不要试图用"多加一个监听端口"绕开它——那需要先设计"源切换语义"（切换时如何清空融合判据）。

**虚拟源的一致性判据不具独立性。** 主项目的位置类准入里有一条"估计器位置 vs 动捕位置"的交叉校验。
虚拟环境下这两份位姿**出自同一份世界状态**（虚拟飞控就是权威），所以它们天然互相印证：

- 可以据此判断"链路是否通、命令是否被接受、门控是否按预期放行"；
- **不能**据此判断链路质量、估计器收敛或真机行为——真机上这条判据恰恰最容易因为安装偏置、
  坐标系错位而失败，而虚拟源上它不可能失败。

反过来同样成立：真机上出现的问题在虚拟源上**不会出现**。主项目自己的实测记录了垂直偏置 6–26 cm、
恒定速度前馈导致约 20 cm 超调（`docs/pva-setpoint-command.md` §4.4.2/§4.4.3），而虚拟飞控的位姿就是
模组的权威状态，没有标定环节可偏。把虚拟源的"全部通过"当成"链路质量已确认"是最容易犯的误读。

| 可以用虚拟源验证 | 不能用虚拟源验证 |
| --- | --- |
| MAVLink 帧格式、CRC、身份与端口配置 | 真实估计器（EKF）的收敛与健康 |
| 准入判据与拒绝状态是否按设计触发 | 飞控参数标定、安装偏置、z 平移偏置 |
| 命令生命周期、ACK 关联、超时与重连 | 前馈/超调在真实控制器上的表现 |
| 轨迹限速、坐标系与单位、前端呈现 | 失效模式（丢包、动捕遮挡、EKF failsafe） |

因此实验记录里应带"数据源类型"字段，不要把虚拟环境的结论混进真机实验记录。

**版本对齐。** 本文的契约按主项目 **1.1.0 之后** 的 `codex/mavlink-official-c` 分支
（`backend/build/drone_backend.exe`）逐条核对，覆盖 `discover_mocap_sources`、`connect_mocap_source`、
`HOLD`/`RESUME`、`set_pva_target` 与起飞序列。模组侧不链接主项目源码，所以主项目升级后需要重新核对的是：
§2.1 的身份比较方式、§4 的控制指令集合、§5 的信标字段、§6.1 的 `type_mask` 处理、§6.2 的准入判据。
`scripts/verify-contract.mjs` 正是为这件事准备的——它能直接跑出上面这些是否还对得上。

**尚未具备的能力**：在前端选中虚拟源**不会**把命令门禁或飞机链路指过去（源管理层与适配器层是两套配置），
所以现在还必须在后端启动参数或 `backend-config.json` 里把适配器的 mocap 端口与飞机端点指到模组。
补齐这件事的需求与验收标准见
[`main-project-request-virtual-source-switch.md`](main-project-request-virtual-source-switch.md)。

## 2. 默认端口和地址

以下配置适用于同一台 Windows 主机上的隔离联调。所有动捕控制和健康端口目前只允许回环地址。

| 用途 | 默认地址 | 模组行为 |
| --- | --- | --- |
| backend MAVLink 接收 | `127.0.0.1:14561` | 模组向该地址发送 MAVLink v1；backend 的命令从模组本地端口返回 |
| 模组 MAVLink 本地端口 | `127.0.0.1:14601` | 固定端口，理由见下；`mini_drone.mavlink.local_port=0` 可改回动态 |
| backend 动捕健康监听 | `127.0.0.1:18151` | 模组周期性发送 JSON 健康信标 |
| 模组动捕控制监听 | `127.0.0.1:18152` | 模组接收 backend 的 `STATUS`/`RECONNECT`/`HOLD`/`RESUME` 探测 |
| 虚拟源 profile 的 source endpoint | `127.0.0.1:15150` | 当前是 profile 元数据，不要求模组额外绑定此端口 |
| 隔离 backend WebSocket | `127.0.0.1:18082` | 仅供 Electron 连接，不由模组使用 |

主项目默认的真实 MAVLink backend 端口可能是 `14540` 或其他运行时值。模组的 `mini_drone.mavlink.remote_port` 必须与正在使用的隔离 backend 监听端口一致，不能凭默认值猜测。

### 2.0 模组本地端口必须固定

backend 会**固定**第一次收到包的来源端点（`peer_pinned`，用来防止局域网里的杂散扫描抢占回程路径），
之后只接受来自该端点的帧。用动态本地端口时，模组每次重启都会换端口，backend 继续往旧端口发包、
把新会话的帧当作不可信来源丢弃，链路永远停在 `session_pending`，只有在前端断开再连接才会恢复。

实测（隔离 backend 不重启）：

| 模组本地端口 | 重启后的结果 |
| --- | --- |
| 动态（`0`） | 30 次命令 / 30 秒内没有恢复，`session_pending`："slot 已发现但尚不可控" |
| 固定 `14601` | 连续三次重启都在**第一条命令**上重新接上 |

所以模组默认绑定固定端口；如果该端口被占用，会回退到动态端口并打一条 warning 说明后果。

### 2.1 身份必须两侧一致（`expected_drone_id`）

backend 只在自身 `expected_drone_id` **非空**时才做过滤，比较方式是**文本逐字比较**：
非字符串广告值会先被序列化成文本（整数 `54` → `"54"`），再与配置值比较。因此

- 模组默认广告字符串 `minecraft_drone_01`（`mini_drone.mocap.expected_drone_id` 可改）；
- 隔离脚本默认给 backend 传同一个值，两侧开箱即一致；
- **不等于**"MAVLink system ID 自动匹配"：如果 backend 配的是 `minecraft_drone_01` 而模组广告 `54`，
  该信标会被**静默丢弃**（backend 不报错，只表现为"未收到信标"）；
- 若希望 backend 配 `--mavlink-mocap-expected-drone-id=54`，模组侧需同时用
  `-Dmini_drone.mocap.expected_drone_id=54`；
- 若希望两侧都不过滤，把 backend 的 `expected_drone_id` 留空即可：`minecraft_drone_01`
  推不出 `ipNN` 后缀，推断结果本来就是空。

排障时可用 `/minidrone link status`（`mocap_expected_id=`）和 `/minidrone mocap status`
（`advertised_id=`）读取模组实际广告值。

## 3. JVM 参数

虚拟动捕接口默认关闭。普通使用不需要修改 JVM 参数；进入世界后执行：

```text
/minidrone mocap enable
```

也可以执行 `/minidrone mocap status`，点击返回消息中的 `[ENABLE]` 或 `[DISABLE]`。这个开关按世界保存，重新进入该世界后会恢复。启用后，主项目执行 `discover_mocap_sources` 即可搜索到虚拟源。

需要启动即开启或运行自动化联调时，才在 Minecraft 实例的 JVM 参数中加入：

```text
-Dmini_drone.mocap.enabled=true
```

可选参数：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `mini_drone.mocap.enabled` | `false` | 启动时强制启用健康信标和控制监听；游戏内停用只对本次运行生效 |
| `mini_drone.mocap.expected_drone_id` | `minecraft_drone_01` | 健康信标里广告的身份；必须与 backend 的 `--mavlink-mocap-expected-drone-id` 逐字一致（见 §2.1） |
| `mini_drone.mocap.health_port` | `18151` | backend 健康监听端口 |
| `mini_drone.mocap.control_port` | `18152` | 模组控制监听端口 |
| `mini_drone.mavlink.remote_host` | `127.0.0.1` | backend MAVLink 地址 |
| `mini_drone.mavlink.remote_port` | `14561` | backend MAVLink 端口 |
| `mini_drone.mavlink.local_port` | `14601` | 模组本地 MAVLink 端口；必须固定，见 §2.0。设 `0` 表示动态分配 |

开发客户端可以这样启动：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot'
.\gradlew.bat runClient
```

进入世界后执行 `/minidrone mocap enable`。自动化联调可以继续把 `-Dmini_drone.mocap.enabled=true` 放到开发启动命令；PCL 或其他启动器若使用该覆盖，则应放到该实例的 Java/JVM 参数中，而不是只放在 Gradle 命令行中。

## 4. 动捕源控制协议

### 4.1 请求

backend 向 `127.0.0.1:18152` 发送 UDP 原始 ASCII 字节，不带 JSON。当前有四条：

| 请求 | 何时发出 | 模组行为 |
| --- | --- | --- |
| `VLT_RELAY_STATUS_V1` | 发现流程、选中源后的只读探测、每 4 秒的前端刷新 | 回显当前状态，不改动任何状态 |
| `VLT_RELAY_RECONNECT_V1` | 操作员要求重新连接动捕源 | 回显 `reconnect`，并**解除**转发保持 |
| `VLT_RELAY_HOLD_FORWARDING_V1` | `disconnect_mavlink_drone` 移除了最后一条链路、且飞行器未解锁 | 暂停位置转发 |
| `VLT_RELAY_RESUME_FORWARDING_V1` | `connect_mavlink_drone` 取回链路时（在端点校验之前） | 恢复位置转发 |

`HOLD`/`RESUME` 是主项目 1.1.0 之后新增的。真机 relay 收到 `HOLD` 会暂停组播位姿；**模组本身就是飞控**，
所以等价语义是"不再接受新的位置/速度设定点"——已经接受的目标继续执行，和真机继续飞向最后一个设定点一致。
模式切换、解锁和降落不受保持影响，操作员任何时候都能把飞机放下来。

**必须应答 `HOLD`/`RESUME`**：backend 只看 `ok=true`，不应答就等同于超时，会被报告成
`mocap.forwarding_hold_failed` 并写一条 warning 事件——一个完全正常的源会因此看起来像故障。

模组应在收到未知命令时忽略，不要让控制线程退出。允许兼容结尾换行，但发送方当前不依赖换行。

### 4.2 响应

响应必须是一个 UTF-8 JSON 对象，`schema` 必须精确为
`mocap_relay_control_v1`，`ok=true` 才会被主项目标记为在线。

最小响应示例：

```json
{
  "schema": "mocap_relay_control_v1",
  "ok": true,
  "action": "status",
  "message": "virtual motion-capture source is online",
  "source_packet_age_ms": 0,
  "safety_latched": false,
  "forwarding_held": false
}
```

字段约定：

- `schema`：必填且固定值。
- `ok`：必填布尔值；服务在线且可以提供当前状态时为 `true`。
- `action`：`status`、`reconnect`、`hold` 或 `resume`，应回显实际处理的动作。
- `message`：供前端诊断显示的简短文本。
- `source_packet_age_ms`：最近位姿数据年龄；刚启动但还没有数据时可以为 `null`。
- `safety_latched`：当前是否处于安全锁存。没有锁存时为 `false`。**转发保持不是安全锁存**，两者独立。
- `forwarding_held`：应用本次请求之后转发是否处于保持；`status` 探测必须回显当前值而不是无条件 `false`。

控制响应只用于发现和状态探测，不要在这里加入无人机 ID、MAVLink system/component ID 或飞行命令。

## 5. 动捕健康信标

模组向 backend 的健康地址（默认 `127.0.0.1:18151`）持续发送 UDP UTF-8 JSON。当前周期为 **50 ms（20 Hz）**，
与 `LOCAL_POSITION_NED` 同拍；backend 将超过 1500 ms 未收到的信标视为过期。

**为什么必须是 20 Hz 而不是主项目 relay 的 10 Hz**：backend 的位置类准入里有一条交叉一致性判据，
把**最新信标里的 `last_forwarded_pose`** 与**最新 `LOCAL_POSITION_NED`** 相比，超过 **0.10 m** 就拒绝。
信标之间的位姿是冻结的，而位置遥测一直在更新，所以最坏偏差就是"一个信标周期内飞过的距离"。

`scripts/verify-contract.mjs --move-mps=1.4` 让模拟飞机以 1.4 m/s 飞行并直接读这条判据，实测：

| 信标周期 | 实测偏差（限 0.10 m） | 结果 |
| ---: | ---: | --- |
| 250 ms（改动前的值） | **0.2408 m** | 超限 → 位置命令被间歇拒绝（另一个探针实测 6 条里 5 条被拒） |
| 100 ms | 约 0.10 m 量级 | 临界，偶发触发 |
| **50 ms（当前值，20 Hz）** | **0.0896 m** | 通过，余量约 10% |

注意这 0.0896 m 是**最坏情况**：验证脚本把位置遥测和信标放在两个互不相关的定时器上，偏差取满一个周期。
模组本体更好——两者在同一个 I/O 循环迭代里检查，几乎同刻采样，真实偏差远小于此。也就是说
50 Hz 的余量来自"即使完全解相关也还在窗口内"，而不是靠相位对齐。

这个偏差与采样相位无关（信标位姿一冻结，位置就继续走），所以不能靠"恰好同时发"来规避。
主项目 relay 用 10 Hz 够用是因为真机实验速度只有 0.15–0.35 m/s；虚拟飞控上限 1.4 m/s，必须更快。
改动这个周期或虚拟飞控的水平限速时，请同步看 `MavlinkTransportMocapHealthTest` 里的不变量测试——
它把这条耦合关系固定下来了。

每条消息的 `schema` 必须为 `mocap_relay_health_v1`。当前兼容的完整示例：

```json
{
  "schema": "mocap_relay_health_v1",
  "source_mode": "minecraft_virtual",
  "wall_time_unix_us": 1787600000000000,
  "healthy": true,
  "safety_latched": false,
  "position_only": false,
  "attitude_source": "hybrid",
  "fusion_mode": "flight_controller_roll_pitch_external_nav_position_yaw",
  "roll_pitch_source": "flight_controller",
  "yaw_source": "motion_capture_external_nav",
  "expected_drone_id": "minecraft_drone_01",
  "tracking_age_ms": 0.0,
  "forward_rate_hz": 20.0,
  "orientation_held": false,
  "tracking_holdover_active": false,
  "field_size_m": [13.0, 13.0],
  "field_centered_at_world_origin": true,
  "field_protocol_version": 1,
  "field_update_wall_time_unix_us": 1789457001797000,
  "forwarding_held": false,
  "forwarding_hold_reason": "",
  "last_source_pose": {
    "position_m": [0.0, 0.0, 0.0],
    "roll_pitch_yaw_rad": [0.0, 0.0, 0.0]
  },
  "last_forwarded_pose": {
    "position_m": [0.0, 0.0, 0.0],
    "roll_pitch_yaw_rad": [0.0, 0.0, 0.0]
  }
}
```

### 5.1 训练场元数据（`field_*`）

主项目前端会在 `adapter.status` 里读这四个字段并**自动**把场地画成对应尺寸
（日志："场地尺寸已从动捕同步：13.00m × 13.00m"）。真机 relay 也是这么发的，
虚拟源以前缺这一段，于是即使飞机在场地里飞，前端画的还是它自己的场地。

| 字段 | 值 | 说明 |
| --- | --- | --- |
| `field_size_m` | `[13.0, 13.0]` | 场地宽 × 深（米），取**实际建成/定义**的占地（1 方块 = 1 米）。默认 13×13；`-Dmini_drone.arena.radius_x/radius_z`（单位方块，默认 6/6）可以建成矩形，例如 `radius_x=10, radius_z=4` → `[21.0, 9.0]`；`/minidrone field set` 可以直接定义任意矩形 |
| `field_centered_at_world_origin` | `true` | 场地中心**就是**虚拟世界原点。主项目只信这一种场地；为 `false` 时它会忽略整段（除非同时给出下面的偏移） |
| `field_center_m` | `[0.0, 0.0]` | 场地中心相对原点的偏移（米），轴系与场景一致（x = −east、z = −north，即 Minecraft 的 X/Z 轴）。中心即原点时必须为 `[0,0]`，此时 `field_centered_at_world_origin=true`；原点被指定到角/任意点时给非零值并置 `false` |
| `field_protocol_version` | `1` | 场地元数据契约版本（`MocapFieldMetadata.CURRENT_PROTOCOL_VERSION`） |
| `field_update_wall_time_unix_us` | 定义/载入时间 | "这份场地定义是什么时候确立的"。场地变更或世界载入时刷新一次，其余时间保持不变（与 relay 的语义一致） |

`MocapFieldMetadata` 会拒绝"既声称以原点为中心、又给出非零偏移"这种自相矛盾的组合：主项目看到
`centered_at_world_origin=true` 就按原点居中来画，两个字段打架时画出来的场地一定是错的。

**矩形场地不需要扩协议**：`field_size_m` 本来就是 `[width, depth]` 两个独立值，主项目前端也分开用宽/深
（`updateFieldDimensions(width, depth)`）。所以矩形只用两个 JVM 参数 + 重建场地即可。
**偏心场地（场地中心 ≠ 世界原点）对虚拟源没有意义**：模组的原点定义就是场地中心（§5.2），
只有真机 relay 才可能出现"动捕原点"和"场地中心"不同的情况，那才需要扩一个 `field_origin_m`。

**没有训练场时不发这四个字段**（`MocapFieldMetadata` 为 null）：此时原点是"玩家前方 2 格"，
去声明一个"以原点为中心的 13 m 场地"是假的，只会把操作员看到的场地画到没有飞机去过的地方。

### 5.2 原点和场地必须重合

主项目把"场地中心"和"命令用的 Local NED 原点"当成同一个点，所以有训练场时：

- 虚拟世界原点 = **场地中心垫面**：X/Z 取垫子方块中心（`centerX+0.5` / `centerZ+0.5`，即 13×13 的几何中心），
  Y 取表面层上方一格（`topY + 1`，静止无人机 `down=0` 的位置）；
- 于是 `LOCAL_POSITION_NED (0,0,0)` = 场地中心的降落垫，飞机相对场地的位置与前端画的场地一致；
- 无人机实体也生成在这个原点（不再"在玩家前方 2 格"）；
- 没有训练场时才退回"玩家前方 2 格"的便利原点。

`/minidrone origin set` 在有训练场时同样按场地中心重算（并打日志说明用的是哪条规则），
`/minidrone arena create` / `arena clear` 会立刻切换规则并重新广播（或清空）场地元数据。

实现注意：`ArenaOrigin.centeredTransform()` 是不依赖 Minecraft 世界类的纯算术，由单测固定；
信标负载由 `MavlinkTransport.mocapHealthPayload(..., MocapFieldMetadata)` 生成，同样有单测。

### 5.3 两个位姿块的分工（`last_source_pose` / `last_forwarded_pose`）

模组现在同时发这两块，语义与 relay 一致：

| 块 | 坐标系 | 用途 |
| --- | --- | --- |
| `last_source_pose.position_m` | **房间朝向轴系**：`(x, y, z) = (−east, −north, up)` | 主项目前端**优先用它**：机型面板的 X/Y/高度、"动捕 (…)" 矢量、以及场景映射 `(x, z, y) → world`。它与 NED 块描述**同一个点**（`world = (−east, −down, −north)` 两边一致） |
| `last_forwarded_pose.position_m` | Local NED：`[north, east, down]` | 后端的一致性判据（信标位姿 vs `LOCAL_POSITION_NED`，窗口 0.10 m） |

虚拟源没有独立的 marker，所以两块的 `roll_pitch_yaw_rad` 是同一个测量值（飞控姿态）；主项目只用它做诊断，
前端显示的姿态始终来自 `ATTITUDE`（消息 30）。

### 5.4 场地元数据消失 = 前端要撤销场地

`arena clear` 之后模组**不再发** `field_*`。主项目把这当成状态变化而不是"没更新"：
前端会把场地恢复成操作员自己设置的尺寸（没设置过就回到内置默认），并打日志
"动捕场地元数据已撤销，恢复本机设置的场地尺寸"。所以清掉训练场不会留下一个"飞机其实没在里面飞"的 13 m 场地。

实现注意事项：

- backend 只接受来自回环地址的健康信标。
- `healthy=true` 且 `safety_latched=false` 才表示可用源。
- `forwarding_held` / `forwarding_hold_reason` 与 relay 保持同名字段语义：表示位置转发是否被 `HOLD` 暂停。
  它**不是** `safety_latched`，也不应让 `healthy` 变 `false`——否则 backend 会连非位置命令一起拒绝。
  被保持时模组仍然照常发信标。
- 如果 backend 的 MAVLink adapter 配置了 `expected_drone_id`，模组广告的值必须与其**逐字相同**。
  backend 做的是文本比较：非字符串广告值先被转成文本（`54` → `"54"`）再比较，因此
  "整数的 MAVLink system ID" 只有在该配置值本身就是 `"54"` 时才匹配，见 §2.1。
  模组默认广告 `minecraft_drone_01`，可用 `mini_drone.mocap.expected_drone_id` 覆盖。
- 主项目当前支持的融合契约是：
  `fusion_mode="flight_controller_roll_pitch_external_nav_position_yaw"`；
  或旧兼容形式 `position_only=true` 且 `attitude_source="flight_controller"`。
- `last_forwarded_pose.position_m` 使用米；`roll_pitch_yaw_rad` 使用弧度。位置约定为 Local NED：`[north, east, down]`。
- 健康 JSON 是状态信标，不替代 MAVLink `ATTITUDE` 或 `LOCAL_POSITION_NED`。

前端显示的飞行姿态主要来自 MAVLink `ATTITUDE`（消息 ID 30）。健康信标中的姿态只用于融合门禁和诊断，不会直接覆盖前端姿态。

## 6. MAVLink 兼容要求

模组继续使用 MAVLink v1 帧，消息 ID 和当前用途如下：

| 消息 | ID | 建议发送频率 | v1 负载长度 | 用途 |
| --- | ---: | ---: | ---: | --- |
| `HEARTBEAT` | 0 | 1 Hz | 9 | backend 发现和绑定虚拟飞控 |
| `ATTITUDE` | 30 | 20 Hz | 28 | 前端/后端姿态显示，弧度 |
| `LOCAL_POSITION_NED` | 32 | 20 Hz | 28 | 权威 Local NED 位置 |
| `EXTENDED_SYS_STATE` | 245 | 5 Hz | 2 | 起飞/降落状态 |
| `SYS_STATUS` | 1 | 2 Hz | 31 | 电池和系统状态 |
| `EKF_STATUS_REPORT` | 193 | 2 Hz | 22 | 估计器状态门禁 |
| `COMMAND_ACK` | 77 | 按命令返回 | 3 | 命令必须有终态 ACK |
| `GPS_GLOBAL_ORIGIN` | 49 | 按请求返回 | 12 | indoor origin 确认（起飞前置） |
| `HOME_POSITION` | 242 | 按请求返回 | 52 | indoor origin 确认（起飞前置） |
| `PARAM_VALUE` | 22 | 按请求返回 | 25 | 融合配置（起飞与位置命令前置） |

**负载长度必须是 MAVLink 1 的长度**（即官方 `MAVLINK_MSG_ID_*_MIN_LEN`）：v1 帧要截断尾部的扩展字段。
`EKF_STATUS_REPORT` 一度带着 `airspeed_variance` 发 26 字节，虽然当前后端容忍，但一旦某个严格解析器丢掉这种帧，
就会连带丢掉 EKF 门禁——而那道门禁拒绝所有位置命令和起飞。同理 `GPS_GLOBAL_ORIGIN` 与 `HOME_POSITION`
不发 `time_usec`：后端的守卫接受 12/52 字节，它自己的 v1 编码器也不发该扩展（发 `SET_GPS_GLOBAL_ORIGIN` 时
会记录 `time_usec_extension_omitted`），而 `time_usec` 在接收侧只进诊断。
`MavlinkMessagesTest#everyPayloadFitsTheMavlinkV1Length` 把这些长度钉住了。

需要 keep 的还有 CRC extra：模组 `MavlinkProtocol.crcExtra` 里每个 ID 的取值都已与后端链接的官方
MAVLink C 库（`third_party/mavlink/c_library_v2`）核对一致。改错一个 extra 的后果是该类消息被**静默丢弃**
（例如改错消息 84 就等于虚拟飞机再也不动）。

当前模组已经实现的主要命令路径包括：

- `GUIDED` 模式切换；
- Arm/Disarm；
- Takeoff/Land；
- `SET_POSITION_TARGET_LOCAL_NED` 的**全部 `type_mask` 组合**（见 §6.1）；
- 参数读取和消息请求；
- Home/Global Origin 查询。

保持以下身份和坐标约定：

```text
MAVLink system/component = 54 / 1
Local NED = [north, east, down]，单位米
Minecraft world.x = -east
Minecraft world.y = -down
Minecraft world.z = -north
```

除非同步修改 backend 配置和测试，不要改变 system ID、component ID、MAVLink v1 CRC extra 或 payload 字段顺序。

### 6.1 PVA 设定点（`set_pva_target`）的 `type_mask`

主项目的 PVA 是同一时刻唯一的连续控制通道，它下发的是 `SET_POSITION_TARGET_LOCAL_NED`（消息 84，
`MAV_FRAME_LOCAL_NED = 1`）。`type_mask` 中**置位的位表示"忽略该通道"**，因此同一帧可以是：

| 提供字段 | `type_mask` |
| --- | ---: |
| 仅位置 | `0x0DF8` (3576) |
| 位置 + 速度 | `0x0DC0` (3520) |
| 位置 + 速度 + 加速度 | `0x0C00` (3072) |
| 仅速度 | `0x0DC7` (3527) |
| 位置 z + 速度 x/y/z | `0x0DC3` (3523) |
| 保持位置，只转偏航（忽略 `yaw_rate`） | `0x0800` (2048) |
| 仅偏航 | `0x09FF` (2559) |
| 全字段 | `0x0000` (0) |

模组必须**逐位**解析，不能按整掩码白名单匹配：只接受一两种掩码会让其余帧被静默丢弃，
表现为"主项目发了指令、虚拟飞机毫无反应"。

模组侧的语义与包络见 `README.md` 的"虚拟飞控包络与设定点语义"：每个设定点替换上一帧，
未被命令的轴保持（不再平移）；位置轴用限速跟踪，速度和加速度作为前馈；偏航按 `yaw_rate`
前馈限速转向。位置跟踪是开关式而非比例控制，所以不要用虚拟源的跟踪精度外推真机。

### 6.2 位置类命令与起飞的准入时间

后端在会话建立后会按 300 ms 间隔排空一份参数清单（`EK3_SRC1_*`、`GUID*`、`WPNAV_*`、电池参数等，共 69 项），
并用车辆回传的 `PARAM_VALUE` 判定"飞控是否在融合外部导航"。在此之前位置类命令会被拒绝：

| 命令 | 拒绝状态 | 缺什么 |
| --- | --- | --- |
| `set_pva_target` / `goto_waypoint` | `external_nav_horizontal_fusion_unstable` | 需要水平融合证据（依赖 `EK3_SRC1_POSXY`） |
| `takeoff` | `takeoff_preflight_unstable` | 起飞前检查更严：还需要 `EK3_SRC1_POSZ` 与 `EK3_SRC1_YAW`，回执里按 `estimator_position_z_source_missing`、`estimator_yaw_source_missing`、`external_nav_fusion_unconfirmed` 逐条列出 |

实测：使用模组的身份、心跳节奏、`PARAM_VALUE` 应答表和健康信标，后端在会话建立后约 **3–4 秒**内
完成该清单并开始放行（`scripts/verify-contract.mjs` 会把耗时打出来）。排障时不要把这前几秒的拒绝当作链路故障；
`/minidrone link status` 显示 `backend_fresh=true` 只说明遥测往返正常。

另外：**起飞序列要求飞行器在确认 indoor Home/Global Origin 之前保持未解锁**。后端在 `awaiting_origin`
阶段一旦发现已解锁就以 `origin_setup_armed_unexpectedly` 结束，所以先点前端「解锁」再点「起飞」不会起飞；
直接用「起飞」按钮走完 请求 origin → GUIDED → ARM → NAV_TAKEOFF。

### 6.2b 飞行中上锁 = 自由落体（急停能解除的前提）

主项目的急停就是一条**强制上锁**命令：`MAV_CMD_COMPONENT_ARM_DISARM`，`param1 = 0`（`param2 = 21196`）。
虚拟飞控过去在这种情况把飞机"停"在空中：`armed=false` 但 `down` 不变。这有两个后果：

1. 游戏里看起来像悬停（模组本来没有重力模型，可以理解）；
2. **解除急停永远不可能成功**——后端 `EmergencyStopReleasePolicy` 要求
   `disarmed && landed_state_fresh && landed`（`MAV_LANDED_STATE_ON_GROUND`）才放行，
   而悬停的飞机一直报 `IN_AIR`，于是操作员会看到"点了释放没反应"。

现在的语义：飞行中一旦上锁，飞机失去推力，按重力加速下坠（最高 8 m/s），触地后
`down = 0`、落地状态转为 `ON_GROUND`，随后：

```text
emergency_stop        -> 强制上锁，飞机下坠到地面
release_emergency_stop-> 需要 disarmed + 新鲜的 ON_GROUND（另有 5 秒安全锁）→ completed
takeoff               -> 重新 GUIDED → ARM → 起飞
```

实测（虚拟源，3 m 起飞后急停）：下坠约 **0.8 s** 触地，释放回执里
`landed=true, landed_state=1, landed_state_fresh=true`，之后 `takeoff` 正常爬到 2.5 m。
这是"不是完整物理模型但结果正确"的最小实现：只做竖直自由落体，不做水平动量、不做姿态失稳。

### 6.3 坐标链与单位（1 方块 = 1 米）

模组不做单位换算：Minecraft 的 1 个方块就是 1 米。同一份位姿要经过三段变换，三段都别各自"解释"：

| 环节 | 变换 | 实现 |
| --- | --- | --- |
| 模组：NED → Minecraft 世界 | `world.x = origin.x − east`；`world.y = origin.y − down`；`world.z = origin.z − north` | `NedWorldTransform` |
| backend：`LOCAL_POSITION_NED` → 内部世界坐标 | `world_x = 偏移量 − east`；`world_y = −down`；`world_z = 偏移量 − north` | `MavlinkAdapterInternalModel.inc` 的 `local_ned` 分支 |
| 前端：世界 → 场景 | `scene.x = −world.x`；`scene.y = world.y`；`scene.z = world.z` | `scene-coordinate-frame.mjs` |

backend 的偏移量只对发过 `GLOBAL_POSITION_INT` 的车辆生效（用它的经纬度对齐共享原点）。模组不发这条消息，
所以偏移量为 0，两侧的世界坐标**逐轴相同**。

`scripts/verify-contract.mjs` 会实测这条链：NED `(north 2, east 1, down −1.5)` → backend 发布
`world (−1, 1.5, −2)` ✓。

由此得到的朝向对应表：

| 方向 | Minecraft 世界（模组） | 前端场景 |
| --- | --- | --- |
| +北（+north） | −Z | −Z |
| +东（+east） | −X | +X |
| 上（−down） | +Y | +Y |

也就是**前端场景 = Minecraft 世界沿 X 轴镜像**（Minecraft 是左手系）。"飞机在北边"在游戏里和场景里都是 −Z；
"飞机在东边"在游戏里是 −X、在场景里是 +X。

姿态：模组把 NED 欧拉角原样放进 `ATTITUDE`（消息 30），backend 转成 NED 四元数发布；前端再用
`mavlinkNedQuaternionToSceneQuaternion` 换到场景系。`NedWorldTransform` 里那个
`180 − yaw°` 只用于 **Minecraft 实体自身的朝向渲染**，不参与遥测，也不要拿它去核对前端显示的航向。

坐标链里"原点在哪"由 §5.2 决定：有训练场时 `NED (0,0,0)` 是场地中心垫面，场地元数据同时告诉前端
场地就是 13×13 m 且以该原点为中心，三者（实体、NED、前端场地）因此重合。

## 7. 发现流程和“在线”条件

Electron 点击“重新扫描”后，主项目执行：

```text
discover_mocap_sources
  -> 创建内置 minecraft_virtual_mocap profile
  -> 向 127.0.0.1:18152 发送 VLT_RELAY_STATUS_V1
  -> 校验 JSON schema 和 ok=true
  -> 前端显示“在线”或“未响应”
```

这不是任意 UDP 端口扫描，也不是广播发现。当前实现没有统一的局域网发现报文，因此模组不需要实现局域网扫描服务。若将来要支持跨主机发现，需要另行设计发现协议，并同时修改 backend 和模组的绑定策略。

## 8. 推荐联调顺序

### A. 只验证模组控制端口

1. 正常退出旧 Minecraft 实例，确保使用最新 JAR。
2. 进入世界后执行 `/minidrone mocap enable`（或用 `-Dmini_drone.mocap.enabled=true` 启动开发覆盖）。
3. 检查端口：

```powershell
Get-NetUDPEndpoint -LocalPort 18152 |
    Select-Object LocalAddress,LocalPort,OwningProcess
```

4. 手动发送控制探测：

```powershell
$client = [System.Net.Sockets.UdpClient]::new()
$client.Client.ReceiveTimeout = 1000
$request = [Text.Encoding]::ASCII.GetBytes('VLT_RELAY_STATUS_V1')
[void]$client.Send($request, $request.Length, '127.0.0.1', 18152)
$remote = [Net.IPEndPoint]::new([Net.IPAddress]::Any, 0)
$response = $client.Receive([ref]$remote)
[Text.Encoding]::UTF8.GetString($response)
$client.Dispose()
```

期望响应中的 `schema` 是 `mocap_relay_control_v1` 且 `ok` 为 `true`。

5. Electron 设置页点击“重新扫描”；`minecraft_virtual_mocap` 应显示在线。

### B. 再验证 MAVLink 和健康信标

1. 启动隔离 backend，并确认它监听模组配置的 MAVLink 端口。
2. 将模组 `mini_drone.mavlink.remote_port` 设置为该端口。
3. 在游戏中执行：

```text
/minidrone link status
/minidrone selftest
```

4. backend 侧应依次出现类似事件：

```text
mavlink.link.up
mavlink.heartbeat.detected
mavlink.binding.established
mavlink.mocap_health.listener_ready
```

5. 检查 backend 状态中的 `mocap_health_beacon_received=true`、`mocap_relay_healthy=true`、`mocap_safety_not_latched=true`。

控制端口在线但没有 MAVLink 心跳，说明模组的源控制服务正常、MAVLink 目标配置仍有问题；不要因此修改动捕发现逻辑。

## 9. 常见故障定位

| 现象 | 优先检查 |
| --- | --- |
| 前端候选“未响应” | 世界内是否执行过 `/minidrone mocap enable`；模组 JAR 是否为最新；`18152` 是否被其他进程占用 |
| `18152` 在线但健康状态没有更新 | `18151` 是否与 backend adapter 配置一致；模组是否持续发送 `mocap_relay_health_v1` |
| 有健康信标但被静默忽略 | `schema` 错误；发送端不是回环地址；**`expected_drone_id` 不是逐字相同**（§2.1）——这是最容易误判为"没在发"的一类 |
| 模组 `/minidrone link status` 的 `backend_fresh=false` | MAVLink remote host/port 错误；隔离 backend 未启动；指向了错误的生产 backend |
| 位置正常但姿态不对 | 先检查 MAVLink `ATTITUDE` 消息 ID 30 和四元数/欧拉角转换；健康 JSON 不负责覆盖前端姿态 |
| 位置命令被拒绝 | 健康信标过期、`healthy=false`、`safety_latched=true`、融合契约字段缺失或 EKF 状态不满足 |
| 位置命令**只在飞行中被拒**（悬停正常） | 信标周期与飞行速度的乘积超过 backend 的 0.10 m 一致性窗口，见 §5；确认模组按 50 ms 发信标 |
| 命令被拒 `external_nav_horizontal_fusion_unstable`，其余都正常 | 后端还没收齐 `EK3_SRC1_*` 参数（会话建立后约 6 秒）——确认模组在响应 `PARAM_REQUEST_READ`，见 §6.2 |
| PVA 下发但虚拟飞机不动 | 检查该帧的 `type_mask` 是否只用了被支持的通道；模组逐位解析，但"全忽略"的帧会被拒绝（见 §6.1） |
| 断开链路时出现 `mocap.forwarding_hold_failed` | 模组没有应答 `VLT_RELAY_HOLD_FORWARDING_V1`（回环地址、schema、`ok=true`），见 §4.1 |
| 重启 Minecraft 后后端命令一直 `session_pending` | 模组这次用了动态本地端口（被占用触发回退），见 §2.0；在前端断开再连接无人机即可恢复，或让 `14601` 空出来 |

## 10. 修改边界

模组侧可以自行修改：

- Minecraft 世界坐标到 Local NED 的映射；
- 虚拟飞控内部状态和运动模型；
- 健康信标中与模组实际状态相关的诊断字段；
- 控制端口的线程、日志和错误恢复实现。

模组侧必须保持：

- `mini_drone.mocap.enabled` 的显式开关；
- `127.0.0.1:18152` 控制探测兼容性，以及 `HOLD`/`RESUME` 的应答；
- `mocap_relay_control_v1` 和 `mocap_relay_health_v1` schema；
- 健康信标的周期性发送、`expected_drone_id` 和安全字段；
- 一个固定的 MAVLink 本地端口（§2.0），否则 backend 无法在模组重启后重新接上；
- MAVLink v1 身份、消息 ID、CRC 和 Local NED 语义。

本阶段不要在模组中加入第二个 backend，也不要把动捕源控制接口绑定到某个无人机槽位。无人机绑定属于 MAVLink adapter 配置；动捕源控制服务只回答“源是否在线以及当前状态”。
