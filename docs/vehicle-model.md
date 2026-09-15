# 虚拟无人机模型（可调参数）

这台飞机过去是**运动学**的：设定点直接变成速度，所有上限都是代码里的常量。现在它是**参数化**的：
每一个影响运动的数字都在 `VehicleModel` 里，有名字、有单位、有默认值，可以在启动参数里覆盖，也可以在游戏里调。

默认值描述的是一台**室内小型四旋翼**（约 30 g、推重比 2.2、电机时间常数 80 ms、倾角上限 35°）——
也就是这块 Minecraft 场地要替代的那类飞机。

## 参数表

| 参数 | 默认 | 单位 | 改它会发生什么 |
| --- | --- | --- | --- |
| `mass_kg` | 0.03 | kg | 越重越"肉"：加速度变小、风阻影响更明显 |
| `thrust_to_weight` | 2.2 | — | 推重比。2.2 = 悬停油门约 45%；越大爬升越猛、回正越快；接近 1.0 就"顶不动" |
| `motor_time_constant_s` | 0.08 | s | 电机改变推力的滞后；和下一个一起决定速度响应快慢 |
| `max_tilt_deg` | 35 | ° | 最大倾角＝能指向水平方向的最大推力比例；越大水平加速越猛 |
| `max_yaw_rate_rad_s` | 3.0 | rad/s | 偏航最快转多快 |
| `attitude_time_constant_s` | 0.12 | s | 姿态/速率环的响应时间；越大越"飘" |
| `drag_coefficient` | 0.06 | N·s²/m² | 二次阻力系数 `F = -k·v·|v|`。**它决定这架飞机真正的最高速**（见下） |
| `max_horizontal_speed_mps` | 1.4 | m/s | 飞控**允许要求**的水平速度上限（不是物理上限）。注意：健康信标周期与它绑定，见 §2 |
| `climb_rate_mps` | 0.8 | m/s | 指令爬升率 |
| `descent_rate_mps` | 0.6 | m/s | 受控下降率；也是"不算坠机"的下限 |
| `collision_half_width_m` | 0.45 | m | 碰撞盒半宽（x/z）。做窄门时调小，但要接受"视觉模型比碰撞盒大" |
| `collision_half_height_m` | 0.175 | m | 碰撞盒半高（y） |
| `restitution` | 0.25 | — | 轻碰时弹回多少 |
| `friction` | 0.6 | — | 擦碰时切向速度保留多少 |
| `crash_horizontal_mps` | 0.8 | m/s | 水平撞击达到这个速度判坠机 |
| `crash_vertical_mps` | 1.5 | m/s | 垂直撞击达到这个速度判坠机 |

**推导量**（不能直接设，由上面算出）：重量 `m·g`、最大推力 `推重比·重量`、悬停油门 `1/推重比`、
**物理最高速** `sqrt(最大推力·sin(最大倾角)/阻力系数)` ≈ **2.49 m/s**（默认值下）。

## 1. 速度是怎么来的（"不硬编码"的含义）

每 tick 的流程：

1. 飞控的需求（位置/速度/加速度前馈）算出**期望速度**，并按 `max_horizontal_speed_mps` 等**合同上限**裁剪；
2. 期望速度再被**阻力终速**裁剪（默认 2.49 m/s）——所以就算你把合同上限改成 5 m/s，这架飞机也飞不到 5；
3. 实际速度以**一阶响应**逼近它，响应时间常数 = `motor_time_constant_s + attitude_time_constant_s`（默认 0.20 s），
   同时受**推力可提供的加速度**限制（`最大推力·sin(倾角)/质量` ≈ 12.4 m/s² 水平、≈ 11.8 m/s² 爬升、9.81 m/s² 下落）。

也就是说：**松杆减速、起速需要时间、顶速由气动决定**——这些以前都不存在（设定点瞬间变成速度）。

## 2. 一个必须记住的耦合：信标周期 ↔ 水平限速

后端用"最新信标里的位姿 vs 最新 `LOCAL_POSITION_NED`"做交叉一致性判据，窗口 **0.10 m**，而信标之间的位姿是冻结的，
所以 `信标周期 × 限速` 必须小于 0.10 m。默认 1.4 m/s × 50 ms = 0.07 m ✔。
**如果你把 `max_horizontal_speed_mps` 调大，就必须同时缩短信标周期**（模组里是 `MavlinkTransport.MOCAP_HEALTH_PERIOD_MS`，
对应单测 `keepsTheHealthBeaconInsideTheBackendConsistencyWindowAtTopSpeed` 会失败并提醒你）。

## 3. 怎么改

启动参数（多人/整合包/脚本都适用）：

```text
-Dmini_drone.vehicle.thrust_to_weight=1.6
-Dmini_drone.vehicle.drag_coefficient=0.12
-Dmini_drone.vehicle.max_tilt_deg=25
```

- 一台"重一点、温柔一点"的飞机：`thrust_to_weight=1.5`、`max_tilt_deg=20`、`attitude_time_constant_s=0.25`
- 一台"灵活的小穿越机"：`thrust_to_weight=3.2`、`max_tilt_deg=45`、`drag_coefficient=0.03`（物理最高速升到 ~3.9 m/s，
  记得同时缩短信标周期，见 §2）
- 想把障碍门做得很窄：`collision_half_width_m=0.2`、`collision_half_height_m=0.1`

非法的值（超出范围、拼错、非数字）会被**忽略并保留默认**，不会阻止世界加载。

## 4. 已经由参数决定的行为（都有单测）

- 期望速度到实际速度的一阶响应与推力加速度上限（`VirtualDroneStateTest`）；
- 水平/垂直包络绝不被突破（即使给了速度+加速度前馈）——**这条曾经因为漏掉一次裁剪而被破坏，测试抓到了它**；
- 悬停油门、由阻力推出的最高速、参数覆盖解析与非法值处理（`VehicleModelTest`）；
- 撞击阈值（`ImpactModel` 从模型取阈值，所以"擦碰不毁、硬撞才毁"的界线也是可调的）。
