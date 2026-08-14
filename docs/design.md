# 设计文档：TaskView 嵌入路径 A

## 1. 背景与问题

车机桌面要在自己的窗口里嵌入第三方 App 的 Activity（画面 + 触摸）。约束条件：

- 目标系统 LineageOS 23.2 / Android 16，**ActivityView 已移除**；
- `com.android.wm.shell.taskview.TaskView` 只能在注册过 TaskOrganizer 的进程里工作，
  手机上 WMShell 运行在 **SystemUI 进程**——launcher 进程无法直接 new TaskView；
- launcher 自己运行在互联服务创建的虚拟屏上，被嵌入的 task 也必须落在同一虚拟屏。

## 2. 路径对比结论

| 路径 | 做法 | 结论 |
| --- | --- | --- |
| **A（采用）** | 自研精简版 AAOS 桥：SystemUI 加导出 Service 作 host，launcher 用 SurfaceView 客户端经 Binder 桥接 | AAOS 已量产验证；输入零转发；改动集中在 SystemUI 新增文件 |
| B | launcher 直连 WMShell（把 launcher 进程变成 shell） | 需要 shell 级 signature 权限与 TaskOrganizer 注册，手机上 WMShell 已在 SystemUI，双 organizer 冲突，不可行 |
| C | 整屏镜像/虚拟屏内再嵌 scrcpy 画面 | 已有 carlink-scrcpy 覆盖整屏场景；应用级嵌入做不到双槽并存与独立 task 管理 |

路径 A 即 AAOS `RemoteCarTaskView` ↔ `RemoteCarTaskViewServerImpl` 的裁剪移植
（客户端去掉 car-lib 依赖，服务端适配手机 SystemUI 的依赖图）。

## 3. 嵌入链路完整流程

```
launcher (虚拟屏进程)                        SystemUI 进程
─────────────────────                        ─────────────────────────────
1. TaskViewServiceClient.bind()
   Intent(action=BIND_TASK_VIEW_SERVICE,
         package="com.android.systemui")
        ────bindService──────────────────>  CarLinkTaskViewService.onBind()
                                            (manifest: exported + MANAGE_TASK_VIEW 权限闸门)

2. createTaskView(clientStub)
        ────sync binder─────────────────>  CarLinkTaskViewService:
                                             ensureManageTaskViewPermission()
                                             (同进程放行 / checkCallingPermission)
                                           → CarLinkTaskViewHost.createTaskView()
                                           → new CarLinkTaskViewServerImpl
                                           → TaskViewFactory.create(...) 异步建 TaskView
                                           ← 立即返回 ICarLinkTaskViewHost binder
                                             (TaskView 未就绪前的调用在服务端排队)

3. CarLinkTaskView(SurfaceView) 加入槽位容器
   surfaceCreated:
     host.notifySurfaceCreated(
         new SurfaceControl(getSurfaceControl(), "carlink-copy"))
        ────oneway binder────────────────>  TaskView.getController().surfaceCreated(copy)
   surfaceChanged:
     host.setWindowBounds(boundsOnScreen) ──> 服务端缓存（startActivity 时取用）

4. 点击应用图标 → onInitialized 后：
   PendingIntent.getActivity(launchIntent)
   ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
   host.startActivity(pi, null, options.toBundle(), null)
        ────oneway binder────────────────>  options 补 BAL：
                                             setPendingIntentBackgroundActivityStartMode(
                                               MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
                                           → TaskView.startActivity(...)
                                           → TaskViewTransitions.startActivity：
                                             prepareActivityOptions 注入 launchCookie /
                                             WINDOWING_MODE_MULTI_WINDOW /
                                             removeWithTaskOrganizer；
                                             wct.sendPendingIntent + 过渡动画

5. task 在虚拟屏创建（displayId 随 options bundle 一路带进 WCT）
   TaskViewTransitions 打开过渡：
     prepareOpen() 通过 TaskViewBase.getCurrentBoundsOnScreen()
     读回第 3 步缓存的客户端 bounds；
     leash reparent 到客户端 SurfaceControl 之下
        ────oneway binder────────────────>  client.onTaskAppeared(taskInfo, leash)
   （launcher 只记录 taskInfo，无需再 reparent——shell 侧已完成）

6. 输入：leash 已挂在 launcher surface 层级
   → InputDispatcher 直接把触摸路由给被嵌入 task（零转发）
   前提：宿主窗口 FLAG_NOT_TOUCH_MODAL
        + CarLinkTaskView.onComputeInternalInsets 在 touchable region 打洞
        （整窗 touchable 减去本视图区域；逻辑同 car-builtin-lib TouchableInsetsProvider）

7. 生命周期：
   task finish/死亡 → TaskViewBase.onTaskVanished → client.onTaskVanished
       → launcher 清空槽位、移除 SurfaceView
   槽位替换/桌面销毁 → host.release()
       → TaskView.removeTask()（从 WM 移除 task）+ TaskView.release()（注销 controller）
   surface 销毁（如槽位隐藏）→ notifySurfaceDestroyed → task 随 surface 隐藏
   surface 重建 → notifySurfaceCreated → controller 自动 setTaskViewVisible(true)
```

## 4. BAL（后台 Activity 启动限制）要点

launcher 在虚拟屏里对用户不可见（系统视角），直接 send PendingIntent 会被 Android 14+ 的
BAL 硬化拦截。AAOS 的解法照抄：SystemUI 侧在 `startActivity` 里给 options 补
`ActivityOptions.setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)`。
该调用在 SystemUI（系统进程上下文）执行，合法且等效于 AAOS 行为；launcher 侧不需要、
也不应该自己设置 BAL mode。

## 5. 双槽模型

- 每槽 = 一个 `CarLinkTaskView` + 一对独立的 host/client binder（`createTaskView` 一次一槽）。
- 主/副槽互不知道对方；task 互不共享 TaskViewTaskController，生命周期互不影响。
- 槽位为空时容器 GONE，SurfaceView 随之销毁 → `notifySurfaceDestroyed` → 服务端 task 隐藏；
  但 v1 策略下"槽位置空"总是伴随 release，不存在保活隐藏 task 的需求。

## 6. 与 AAOS 的差异（裁剪说明）

手机 SystemUI 的 Dagger 图（SysUIComponent）**不暴露** AAOS 服务端直接注入的
ShellTaskOrganizer / TaskViewTransitions / SyncTransactionQueue——可注入的 WMShell 入口只有
`Optional<TaskViewFactory>`（SystemUIInitializer 从 WMComponent 搬入）。因此：

| AAOS 能力 | 本实现 | 说明 |
| --- | --- | --- |
| 直接 new TaskViewTaskController | TaskViewFactory.create → TaskView，替换其 TaskViewBase 为本桥 | TaskView 自身的 view 永不 attach |
| release 时 removeTask | `TaskView.removeTask()` + `release()` | 等效 |
| createRootTask(displayId)（RootTaskMediator） | **不支持**，服务端 log + 忽略 | 需要 ShellTaskOrganizer.createRootTask；v1 只嵌普通 activity task，故 RootTaskMediator 未移植 |
| setWindowBounds 运行时改 task bounds | 仅缓存，下一次 startActivity 生效 | 需要 TaskViewTransitions.setTaskBounds，手机 SystemUI 拿不到；**v1 已知限制**：槽位尺寸变化（如副槽加入导致主槽收窄）时被嵌入 task 不会即时跟随缩放 |
| setTaskVisibility / showEmbeddedTask | 服务端 no-op | 可见性跟随 surface；槽位不重叠故无需置前 |
| addInsets/removeInsets | 未纳入 AIDL | v1 无 caption/自绘 insets 需求 |
| client 侧 getCurrentBoundsOnScreen / setResizeBackgroundColor 回调 | 服务端 bounds 缓存 + resize 事务本地 apply | 减少同步 IPC；事务本就是 shell 侧构建的，本地 apply 等效 |

## 7. 权限与进程模型

- 自定义签名权限 `com.carlink.permission.MANAGE_TASK_VIEW`（由 launcher 定义）：
  manifest 闸门（bind 时校验）+ `createTaskView` 内 `checkCallingPermission` 二次校验，
  SystemUI 进程内调用放行（`Binder.getCallingPid() == Process.myPid()`）。
- launcher 与 SystemUI 各自编译同一份 `com.carlink.taskview` AIDL 契约。
- 断线：SystemUI 死亡 → linkToDeath/onServiceDisconnected → launcher 清空槽位并延迟重连
  （重连骨架：1s 后重 bind；重新嵌入由用户点击触发）。
