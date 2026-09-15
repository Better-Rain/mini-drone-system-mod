# Mini Drone System Mod

这是 `mini-drone-system` 的 Minecraft 虚拟训练附属项目。主项目前端和 C++ 后端保持不变，通过 MAVLink UDP 控制 Minecraft 中的虚拟飞控；模组负责飞行状态、遥测和虚拟动捕环境。

```text
Electron/Vite 前端 -> WebSocket v1 -> C++ Backend -> MAVLink UDP -> Fabric 模组
```

主项目仍是权威状态源，负责安全门禁、命令生命周期、ACK/结果关联和 UI。模组不会绕过主项目直接接受前端控制。

## 技术基线

- Minecraft 1.21.1
- Fabric Loader 0.19.3
- Fabric API 0.116.15+1.21.1
- Fabric Loom 1.6.11
- Gradle 8.7
- Java 21

术语与主项目前端保持一致：**解锁 = arm**（`armed=true`）、**上锁／未解锁 = disarm**（`armed=false`）。
涉及安全状态的说明一律带上 `armed=` 取值，避免"已解锁"被读成"可以起飞"。

## 当前阶段

当前版本是协议、飞控与可视化 MVP，已经实现：

- MAVLink v1 编解码、CRC 校验和主项目黄金帧测试
- GUIDED、Arm/Disarm、Takeoff、Land 和本地 NED 位置目标
- 主项目 PVA 设定点（`set_pva_target`）的全部通道：位置、速度、加速度、偏航和偏航角速度，按 `type_mask` 逐位解析
- `HEARTBEAT`、`ATTITUDE`、`LOCAL_POSITION_NED`、`SYS_STATUS`
- `EKF_STATUS_REPORT`、`EXTENDED_SYS_STATE`
- `COMMAND_ACK`、参数读取、Home 和 Global Origin 遥测
- 可选的主项目动捕健康信标和源控制端点
- Minecraft 服务端无人机实体与 Local NED 坐标同步
- 客户端四旋翼模型、旋翼动画、状态灯和名称状态显示
- 首位进入主世界的玩家前方自动建立虚拟飞行原点
- LOCAL_NED 位置目标的限速渐进运动（水平 1.4 m/s、垂直 0.8/0.6 m/s）
- 水平运动对应的四旋翼倾斜姿态和速度遥测
- 游戏内 `/minidrone status` 状态检查命令
- 游戏内 `/minidrone link status` 后端连接状态和收发计数检查
- 游戏内 `/minidrone selftest` 一次运行虚拟飞行和训练场闭环自检
- 游戏内 `/minidrone origin set` 原点重设命令（仅 SAFE 落地状态）
- 游戏内 `/minidrone arena create|clear` 训练场生成与安全清理命令
- 训练场 3x3 降落标记和 `/minidrone arena status` 中心坐标查询

障碍物、自动降落判定和更完整的姿态控制尚未实现，将在后续阶段加入。

## 使用边界

先把三条前提说清楚，避免把虚拟源的结论用错地方：

- **主项目同一时刻只持有一个动捕源**：真实动捕（15151/15152）与虚拟源（18151/18152）不能同时被同一个后端消费。
  要与真机并存，就给模组另起一个隔离后端实例。
- **虚拟源的一致性判据不具独立性**：进入准入的"飞控位姿 vs 动捕位姿"出自同一份世界状态，所以它天然通过。
  虚拟源能验证协议、门控、轨迹与前端呈现；**不能**用来推断真机的估计器健康、标定偏差或失效模式
  （真机上的垂直偏置 6–26 cm、前馈约 20 cm 超调，在虚拟源上都不会出现）。
- 实验记录请带"数据源类型"，不要把虚拟环境的结论混进真机记录。

详见 [`docs/main-backend-compatibility.md`](docs/main-backend-compatibility.md) §1.1。

## 虚拟飞控包络与设定点语义

虚拟飞控把每个 PVA 设定点当作**完整的一帧**：`type_mask` 里置位的通道表示"忽略"，未置位的通道才是本帧命令。一帧只命令 `yaw` 时，水平轴就没有命令，飞行器保持当前位置不再平移。

| 项 | 包络 |
| --- | --- |
| 水平速度 | 1.4 m/s（圆周包络，不是每轴单独限速） |
| 垂直速度 | 上行 0.8 m/s、下行 0.6 m/s |
| 加速度 | 水平 2.0 m/s²、垂直 1.0 m/s² |
| 偏航角速度 | 1.5 rad/s |
| 水平位置范围 | 距原点 120 m |

未被位置通道使用的速度/加速度会被馈送到该轴的命令里。位置跟踪本身是**限速开关式**的（不是比例控制器），所以"固定位置目标 + 恒定速度前馈"会产生几厘米量级的来回摆动；主项目真机上同一用法会产生约 20 cm 超调（见 `docs/pva-setpoint-command.md` §4.4.3）。**这是模型简化，不是真机的定量等价**，不要把虚拟源的跟踪精度外推到真机。

包络也决定了健康信标的周期：主项目用"最新信标位姿 vs 最新飞控位置"做交叉一致性判据（限 0.10 m），而信标之间的位姿是冻结的，所以周期乘以水平限速必须留在 0.10 m 以内——1.4 m/s × 50 ms = 0.07 m，实测最坏 0.0896 m，250 ms 则实测 0.2408 m 并开始间歇拒绝。详见 `docs/main-backend-compatibility.md` §5，可用 `node scripts/verify-contract.mjs --move-mps=1.4` 复现。

## 构建

需要 Java 21。Windows PowerShell：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot'
.\gradlew.bat test
.\gradlew.bat closedLoopTest
.\gradlew.bat build
```

生成的可加载 JAR 位于：

```text
build/libs/mini-drone-system-mod-0.7.0.jar
```

## 安装到 PCL

在构建完成后，可创建一个与其他游戏版本隔离的 PCL 实例：

```powershell
.\scripts\install-pcl-instance.ps1
```

脚本自动取 `build\libs` 下最新的 `mini-drone-system-mod-*.jar`（跳过 `-sources`／`-javadoc`），
并用 `-ModJar` 可以指定别的构建产物。复制前会校验它确实是可加载的模组 JAR（含 `fabric.mod.json`
与已编译类），避免把 sources JAR 装进实例、等到启动才发现。

默认安装位置为：

```text
C:\Users\VLT_BR\Saved Games\Minecraft\.minecraft\versions\Mini Drone System 1.21.1
```

脚本会安装 Fabric Loader 配置、Fabric API 和当前构建的模组 JAR，并校验下载文件。它不会修改 PCL 的账号和全局启动设置。PCL 首次启动该实例时会补齐 Minecraft 1.21.1 的客户端、依赖库和资源；该版本要求 Java 21。

更新模组前应先正常退出 Minecraft，然后重新执行安装脚本。脚本只替换该实例中名称匹配 `mini-drone-system-mod-*.jar` 的旧版本，不会修改其他模组。

PCL 会因为实例目录已有 `mods` 自动开启版本隔离。虚拟动捕默认关闭。进入世界后可使用聊天命令完成设置，不需要修改启动器：

```text
/minidrone mocap status
/minidrone mocap enable
/minidrone mocap disable
```

`status` 返回的 `[ENABLE]`、`[DISABLE]` 按钮可以直接点击。设置保存在当前世界；重新进入该世界后仍然有效。启用后再让主项目执行“重新扫描”，即可发现 `minecraft_virtual_mocap`。

开发者也可以在启动时使用 JVM 参数强制打开（这适合隔离联调，不是普通用户的必要步骤）：

```text
-Dmini_drone.mocap.enabled=true
```

## 默认网络配置

| 配置 | 默认值 |
| --- | --- |
| 虚拟无人机 ID | `minecraft_drone_01` |
| 动捕 `expected_drone_id`（模组广告） | `minecraft_drone_01` |
| MAVLink system/component | `54 / 1` |
| 模组本地 UDP 端口 | `127.0.0.1:14601` |
| 后端 MAVLink 监听 | `127.0.0.1:14561` |
| 后端 WebSocket（建议隔离值） | `127.0.0.1:18082` |
| 动捕健康监听 | `127.0.0.1:18151` |
| 动捕源控制 | `127.0.0.1:18152` |

模组从 `127.0.0.1:14601` 向 `14561` 发送心跳，后端从收到的心跳学习返回端点并把命令发回模组。

这个本地端口是**固定**的而不是动态分配的：后端会把第一次收到包的来源端点固定下来（防止局域网里的杂散扫描抢占回程），
所以模组每次重启都换端口的后果是——后端继续往旧端口发包，新会话被当成陌生来源丢弃，必须在前端断开再连接才会恢复。
固定端口让"重启 Minecraft 后自动接上"成立；如果该端口被别的程序占用，模组会回退到动态端口并在日志里明确说明。

后端断开最后一条链路时会给 `18152` 发 `VLT_RELAY_HOLD_FORWARDING_V1`，取回链路时发 `VLT_RELAY_RESUME_FORWARDING_V1`。模组会应答，并在被保持期间**不再接受新的位置/速度设定点**（模式、解锁（`arm`）、降落仍然可用），同时把 `forwarding_held` 写进健康信标。`/minidrone link status` 的 `mocap_forwarding=` 可以直接读到当前状态。

## 安全隔离

虚拟动捕健康信标和独立源控制服务默认关闭。推荐进入世界后使用 `/minidrone mocap enable`；仅在需要启动即开启或进行自动化联调时使用 JVM 覆盖：

```powershell
 .\gradlew.bat runClient
```

不要把该开关用于连接真实无人机或真实动捕的后端。附带的隔离启动脚本只使用回环地址，并在 `14561`、`18151` 或隔离 WebSocket 端口已占用时停止，不会关闭已有进程。

开启后，`127.0.0.1:18152` 会响应 `VLT_RELAY_STATUS_V1`、`VLT_RELAY_RECONNECT_V1`，以及后端在断开链路时发的 `VLT_RELAY_HOLD_FORWARDING_V1` / `VLT_RELAY_RESUME_FORWARDING_V1`。这是动捕软件源自己的在线探测，不依赖后端是否收到虚拟无人机 MAVLink 心跳。

## 本地联调

完整的分步操作、每步的期望结果和故障对照在 [`docs/live-run-guide.md`](docs/live-run-guide.md)；下面是骨架。
上机前建议先跑一次只读预检，它会拦住"构建了新版本却忘了重装、实例里还是旧 JAR"这类白白浪费一次上机的问题：

```powershell
.\scripts\preflight.ps1
```

1. 启动主项目后端，参数用虚拟那一套（做法与完整参数表见联调手册 §2.1）：

```powershell
.\scripts\start-isolated-backend.ps1
```

这不是"第二个后端"，而是**主项目后端按虚拟参数启动**：同一个 `drone_backend.exe`。
参数才是设计——你也可以把这组参数直接加进平时的启动命令，继续用 `8080`。
**同一时刻只能有一个 backend 持有 `18151`/`14561`**，模组只往这两个端口发，谁拿着谁才是它的后端。
模组本身从不运行后端。

打包版用户不需要命令行参数：打包版从 `backend-config.json` 生成参数，把那几个字段指到模组即可
（见联调手册 §2.1b，含一份可直接抄的配置）。

2. 启动 Minecraft。推荐直接用 PCL 的实例（本机一直可用、不需要 Gradle 联网）：

```text
启动 PCL 的「Mini Drone System 1.21.1」实例（需 Java 21）
```

也可以用开发客户端，但**首次运行需要联网**拉取客户端运行时依赖（如 `jline-terminal`）：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot'
.\gradlew.bat runClient
```

进入世界后执行 `/minidrone mocap enable`（按世界保存）；自动化联调也可以把 `-Dmini_drone.mocap.enabled=true` 放回启动命令。

3. 主项目前端使用查询参数连接隔离后端：

```text
?backendWs=ws://127.0.0.1:18082
```

4. 在"动捕源"里选中 `minecraft_virtual_mocap`。应先观察以下事件，再发送控制命令：

```text
mavlink.link.up
mavlink.heartbeat.detected
mavlink.binding.established
mavlink.mocap_health.listener_ready
```

随后按 `GUIDED -> ARM -> TAKEOFF -> LAND` 的顺序验证（前端的「起飞」按钮会一次走完前三步）。任何真实飞行操作都不属于本项目的自动测试范围。
建链后的前几秒，位置命令与起飞都会被后端拒绝（它在排空参数清单，起飞还需要额外的两个参数），属正常现象，见联调手册 §3。

### 不启动 Minecraft 也能验证契约

契约的模组侧可以单独跑——脚本扮演虚拟飞机和虚拟动捕源，端口与节奏与模组一致，并按操作员的顺序
走完整个会话（发现源 → 参数清单 → GUIDED → 起飞 → PVA → 状态回读 → 降落 → 断开/重连）：

```powershell
node .\scripts\verify-contract.mjs
node .\scripts\verify-contract.mjs --move-mps=1.4   # 加上"飞行中"的一致性检查
```

共 16 项检查，全过返回 0，失败时返回 1 并列出失败项（摘要里还带 backend 的最后一条命令回执）。
开头两项是离线自检：脚本用自己实现的编码器重建模组的黄金帧，与 `MavlinkV1CodecTest` 里的字节逐位比对——
它们不过就说明后面所有结论都不可信。
它占用模组的那几个端口，所以**先退出 Minecraft 再跑**：backend 只认它学到的那个来源端点，
脚本和运行中的模组不能并存。

## JVM 配置项

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `mini_drone.mavlink.remote_host` | `127.0.0.1` | 后端 MAVLink 地址 |
| `mini_drone.mavlink.remote_port` | `14561` | 后端 MAVLink 端口 |
| `mini_drone.mavlink.local_port` | `14601` | 模组绑定端口，必须固定才不会让后端把回程包发到旧端口；设 `0` 表示动态分配（重启后需要在前端重连） |
| `mini_drone.mocap.enabled` | `false` | 启动时强制启用虚拟动捕健康信标和源控制服务；游戏内停用只对本次运行生效 |
| `mini_drone.mocap.expected_drone_id` | `minecraft_drone_01` | 健康信标广告的身份；backend 侧 `--mavlink-mocap-expected-drone-id` 必须与它逐字一致，否则信标被静默丢弃 |
| `mini_drone.mocap.health_port` | `18151` | 健康信标目标端口 |
| `mini_drone.mocap.control_port` | `18152` | 本机动捕源控制监听端口 |

通过 Gradle 的 `-Dmini_drone.*` 参数会被转发给 Loom 启动的游戏 JVM。打包后使用其他 Minecraft 启动器时，应在该启动器的 JVM 参数中设置这些属性。

## 坐标约定

飞控和后端使用 Local NED（米）。Minecraft/主项目世界坐标映射为：

```text
world.x = -east
world.y = -down
world.z = -north
```

当前状态机内部保存 NED；实体同步器会在 Minecraft 服务端 tick 中应用上述转换。
1 个方块 = 1 米，模组不做单位换算。前端场景坐标在"世界"基础上**沿 X 轴镜像**
（`scene.x = -world.x`），所以"+东"在游戏里是 −X、在前端场景里是 +X。
完整的坐标链、朝向对应表和实测值见 [`docs/main-backend-compatibility.md`](docs/main-backend-compatibility.md) §6.3。

无人机在首位非旁观者玩家进入主世界后生成，其初始位置位于玩家视线前方约 2 米。该位置就是本次服务端会话的 Local NED 原点；模组关闭或世界退出后实体不会写入存档。

## 游戏内调试命令

命令需要开启作弊或拥有相应权限：

```text
/minidrone status
/minidrone link status
/minidrone selftest
/minidrone origin set
/minidrone arena create
/minidrone arena create <x> <y> <z>
/minidrone arena status
/minidrone arena clear
```

`origin set` 会将原点移动到执行命令玩家水平视线前方约 2 米，并把虚拟飞控的 Local NED 位置清零。为避免飞行中坐标系突变，该命令只接受已落地且**未解锁**（`armed=false`，即已上锁）的无人机。

成功的调试命令返回末尾都有 `[COPY]` 按钮。点击后会把完整返回文本复制到系统剪贴板，便于提交调试日志；按钮悬停时会显示复制提示。

`arena create` 默认在玩家水平视线前方约 10 格、目标柱的无树叶地表建立平台；也可以用 `/minidrone arena create <x> <y> <z>` 指定平台中心方块坐标。它会建立 13x13 平整训练平台：平滑石平台、红色边界线、四角海晶灯标记和中心 3x3 降落标记（白色边框、黑色中心）。模组会把实际放置的方块保存到世界数据中；`arena status` 可复制当前中心坐标；`arena clear` 只删除仍保持模组生成状态的已登记方块，玩家替换或破坏过的方块会被保留。训练场已存在时必须先清理，避免误覆盖其他建筑。

中心选择工具列入后续阶段：计划增加一个专用选择器，右键方块记录场地中心，再通过 `arena create selected` 生成，减少手工输入坐标；当前版本的绝对坐标命令仍是确定性调试入口。

**训练场就是虚拟世界坐标系的原点**：有训练场时，`LOCAL_POSITION_NED (0,0,0)` 是场地中心的降落垫面，无人机实体也生成在那里，模组同时把场地尺寸与"以原点为中心"通过健康信标告诉主项目——前端因此会自动把场地画成对应尺寸并让飞机落在场地中心。没有训练场时退回"玩家前方 2 格"的便利原点，并且**不上报**场地，前端保留它自己的场地。`arena create` / `arena clear` 会立刻切换原点规则并重新广播（或清空）场地元数据；细节见 `docs/main-backend-compatibility.md` §5.1–§5.2。

**矩形训练场**：默认 13×13，两个轴可以独立配置（单位是方块，默认 6）：

```text
-Dmini_drone.arena.radius_x=10 -Dmini_drone.arena.radius_z=4   # 建成 21x9 的跑道式场地
```

配置只影响**新建**场地；尺寸随场地一起存进世界数据，所以改参数不会让已建好的场地"变形"。
改动后先 `/minidrone arena clear` 再 `/minidrone arena create` 重建即可，`arena status` / `arena create`
的回执都会打印实际尺寸，模组也按实际占地广播 `field_size_m`（矩形不需要扩协议，主项目本来就分开读宽/深）。
场地中心（降落垫）始终是虚拟世界原点，所以"偏心场地"在虚拟源里不存在；只有真机 relay 才可能需要它。

`selftest` 不会改变当前世界中的无人机、场地或主项目连接。它在内存中运行 10 项闭环检查：GUIDED/ARM 门禁、起飞、限速航点、PVA 速度通道、PVA 偏航通道、降落后上锁（`armed=false`）、Local NED 重置、MAVLink 心跳编解码、坐标变换和训练场布局。训练场存档往返由世界加载/保存路径负责。开发阶段可直接运行 `\.\gradlew.bat closedLoopTest`，进入游戏后只需执行一次 `/minidrone selftest` 即可确认客户端加载了同一套逻辑。

一次启动的建议验证顺序：

```text
/minidrone selftest
/minidrone status
/minidrone link status
/minidrone arena status
```

只有需要验证场地视觉布局时才执行 `arena create`；需要重新定位时使用带坐标的 `arena create <x> <y> <z>`，不必反复退出并重启游戏。
