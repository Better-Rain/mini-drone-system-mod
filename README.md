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

## 当前阶段

当前版本是协议、飞控与可视化 MVP，已经实现：

- MAVLink v1 编解码、CRC 校验和主项目黄金帧测试
- GUIDED、Arm/Disarm、Takeoff、Land 和本地 NED 位置目标
- `HEARTBEAT`、`ATTITUDE`、`LOCAL_POSITION_NED`、`SYS_STATUS`
- `EKF_STATUS_REPORT`、`EXTENDED_SYS_STATE`
- `COMMAND_ACK`、参数读取、Home 和 Global Origin 遥测
- 可选的主项目动捕健康信标
- Minecraft 服务端无人机实体与 Local NED 坐标同步
- 客户端四旋翼模型、旋翼动画、状态灯和名称状态显示
- 首位进入主世界的玩家前方自动建立虚拟飞行原点
- LOCAL_NED 位置目标的限速渐进运动（水平 1.4 m/s、垂直 0.8/0.6 m/s）
- 水平运动对应的四旋翼倾斜姿态和速度遥测
- 游戏内 `/minidrone status` 状态检查命令
- 游戏内 `/minidrone selftest` 一次运行虚拟飞行和训练场闭环自检
- 游戏内 `/minidrone origin set` 原点重设命令（仅 SAFE 落地状态）
- 游戏内 `/minidrone arena create|clear` 训练场生成与安全清理命令
- 训练场 3x3 降落标记和 `/minidrone arena status` 中心坐标查询

障碍物、自动降落判定和更完整的姿态控制尚未实现，将在后续阶段加入。

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
build/libs/mini-drone-system-mod-0.6.0.jar
```

## 安装到 PCL

在构建完成后，可创建一个与其他游戏版本隔离的 PCL 实例：

```powershell
.\scripts\install-pcl-instance.ps1
```

默认安装位置为：

```text
C:\Users\VLT_BR\Saved Games\Minecraft\.minecraft\versions\Mini Drone System 1.21.1
```

脚本会安装 Fabric Loader 配置、Fabric API 和当前构建的模组 JAR，并校验下载文件。它不会修改 PCL 的账号和全局启动设置。PCL 首次启动该实例时会补齐 Minecraft 1.21.1 的客户端、依赖库和资源；该版本要求 Java 21。

更新模组前应先正常退出 Minecraft，然后重新执行安装脚本。脚本只替换该实例中名称匹配 `mini-drone-system-mod-*.jar` 的旧版本，不会修改其他模组。

PCL 会因为实例目录已有 `mods` 自动开启版本隔离。虚拟动捕仍默认关闭；完整联调时只在这个实例的 JVM 参数中加入：

```text
-Dmini_drone.mocap.enabled=true
```

## 默认网络配置

| 配置 | 默认值 |
| --- | --- |
| 虚拟无人机 ID | `minecraft_drone_01` |
| MAVLink system/component | `54 / 1` |
| 模组本地 UDP 端口 | 动态分配 |
| 后端 MAVLink 监听 | `127.0.0.1:14561` |
| 后端 WebSocket（建议隔离值） | `127.0.0.1:18082` |
| 动捕健康监听 | `127.0.0.1:15151` |

模组从动态本地端口向 `14561` 发送心跳，后端从收到的心跳学习返回端点并把命令发回模组。

## 安全隔离

虚拟动捕健康信标默认关闭。仅在确认使用隔离后端时显式启用：

```powershell
.\gradlew.bat runClient -Dmini_drone.mocap.enabled=true
```

不要把该开关用于连接真实无人机或真实动捕的后端。附带的隔离启动脚本只使用回环地址，并在 `14561`、`15151` 或隔离 WebSocket 端口已占用时停止，不会关闭已有进程。

## 本地联调

1. 启动隔离主项目后端：

```powershell
.\scripts\start-isolated-backend.ps1
```

2. 在另一个终端启动 Minecraft 开发客户端和虚拟动捕：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot'
.\gradlew.bat runClient -Dmini_drone.mocap.enabled=true
```

3. 主项目前端使用查询参数连接隔离后端：

```text
?backendWs=ws://127.0.0.1:18082
```

应先观察以下事件，再发送控制命令：

```text
mavlink.link.up
mavlink.heartbeat.detected
mavlink.binding.established
mavlink.mocap_health.listener_ready
```

随后按 `GUIDED -> ARM -> TAKEOFF -> LAND` 的顺序验证。任何真实飞行操作都不属于本项目的自动测试范围。

## JVM 配置项

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `mini_drone.mavlink.remote_host` | `127.0.0.1` | 后端 MAVLink 地址 |
| `mini_drone.mavlink.remote_port` | `14561` | 后端 MAVLink 端口 |
| `mini_drone.mavlink.local_port` | `0` | 模组绑定端口，`0` 表示动态分配 |
| `mini_drone.mocap.enabled` | `false` | 启用虚拟动捕健康信标 |
| `mini_drone.mocap.health_port` | `15151` | 健康信标目标端口 |

通过 Gradle 的 `-Dmini_drone.*` 参数会被转发给 Loom 启动的游戏 JVM。打包后使用其他 Minecraft 启动器时，应在该启动器的 JVM 参数中设置这些属性。

## 坐标约定

飞控和后端使用 Local NED（米）。Minecraft/主项目世界坐标映射为：

```text
world.x = -east
world.y = -down
world.z = -north
```

当前状态机内部保存 NED；实体同步器会在 Minecraft 服务端 tick 中应用上述转换。

无人机在首位非旁观者玩家进入主世界后生成，其初始位置位于玩家视线前方约 2 米。该位置就是本次服务端会话的 Local NED 原点；模组关闭或世界退出后实体不会写入存档。

## 游戏内调试命令

命令需要开启作弊或拥有相应权限：

```text
/minidrone status
/minidrone selftest
/minidrone origin set
/minidrone arena create
/minidrone arena create <x> <y> <z>
/minidrone arena status
/minidrone arena clear
```

`origin set` 会将原点移动到执行命令玩家水平视线前方约 2 米，并把虚拟飞控的 Local NED 位置清零。为避免飞行中坐标系突变，该命令只接受已落地且已解锁的无人机。

成功的调试命令返回末尾都有 `[COPY]` 按钮。点击后会把完整返回文本复制到系统剪贴板，便于提交调试日志；按钮悬停时会显示复制提示。

`arena create` 默认在玩家水平视线前方约 10 格、目标柱的无树叶地表建立平台；也可以用 `/minidrone arena create <x> <y> <z>` 指定平台中心方块坐标。它会建立 13x13 平整训练平台：平滑石平台、红色边界线、四角海晶灯标记和中心 3x3 降落标记（白色边框、黑色中心）。模组会把实际放置的方块保存到世界数据中；`arena status` 可复制当前中心坐标；`arena clear` 只删除仍保持模组生成状态的已登记方块，玩家替换或破坏过的方块会被保留。训练场已存在时必须先清理，避免误覆盖其他建筑。

中心选择工具列入后续阶段：计划增加一个专用选择器，右键方块记录场地中心，再通过 `arena create selected` 生成，减少手工输入坐标；当前版本的绝对坐标命令仍是确定性调试入口。

`selftest` 不会改变当前世界中的无人机、场地或主项目连接。它在内存中运行 8 项闭环检查：GUIDED/ARM 门禁、起飞、限速航点、降落解锁、Local NED 重置、MAVLink 心跳编解码、坐标变换和训练场布局。训练场存档往返由世界加载/保存路径负责。开发阶段可直接运行 `\.\gradlew.bat closedLoopTest`，进入游戏后只需执行一次 `/minidrone selftest` 即可确认客户端加载了同一套逻辑。

一次启动的建议验证顺序：

```text
/minidrone selftest
/minidrone status
/minidrone arena status
```

只有需要验证场地视觉布局时才执行 `arena create`；需要重新定位时使用带坐标的 `arena create <x> <y> <z>`，不必反复退出并重启游戏。
