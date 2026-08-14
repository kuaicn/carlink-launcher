# CarLink 车机桌面（carlink-launcher）

车机互联方案中的**车机侧桌面**：一个运行在车机虚拟屏内的 privapp（`com.carlink.launcher`），
左栏列出手机已安装应用，主/副两个内容槽通过 SystemUI 侧的 TaskView 桥嵌入第三方 App 的
Activity，把任意手机应用"投"进车机屏幕使用。

## 在整体方案中的位置

```
车机 ──连接──> 手机 com.carlink.interconnect（vendor/carlink/interconnect）
                    │  创建 VirtualDisplay
                    │  getLaunchIntentForPackage("com.carlink.launcher")
                    │  + ActivityOptions.setLaunchDisplayId(displayId)
                    ▼
              com.carlink.launcher（本仓库，运行在虚拟屏内）
                    │  bind SystemUI 导出服务（路径 A 桥）
                    ▼
              SystemUI 进程内的 CarLinkTaskViewHost
                    │  TaskView / TaskViewTaskController / TaskViewTransitions
                    ▼
              第三方 App 的 Activity（task 落在虚拟屏，画面嵌入槽位，触摸直达）
```

- 与 **carlink-scrcpy** 的关系：scrcpy 方案负责整屏镜像；本模块负责"应用级"嵌入（每个槽位是
  一个独立 task，可两个并存），两者互补。
- 与 **互联服务** 的关系：互联服务只负责建虚拟屏并把本桌面拉起到该屏；桌面之后的嵌入、
  输入、生命周期全部由本仓库 + SystemUI 补丁完成。

## 目录结构

```
carlink-launcher/
├── Android.bp                  # android_app: CarLinkLauncher（platform 签名、privileged）
├── AndroidManifest.xml         # MAIN+LAUNCHER、MANAGE_TASK_VIEW 签名权限、QUERY_ALL_PACKAGES
├── src/com/carlink/
│   ├── taskview/               # 跨进程契约 AIDL 三件套（launcher 与 SystemUI 共用；
│   │                           #   置于 src/ 下以落入 Soong 默认 AIDL 导入搜索路径）
│   └── launcher/
│       ├── LauncherActivity.java   # 桌面主体：应用列表 + 双槽管理
│       ├── AppInfo.java / AppListAdapter.java   # 纯 framework View 的列表
│       └── taskview/
│           ├── CarLinkTaskView.java           # SurfaceView 客户端（改自 AAOS RemoteCarTaskView）
│           └── TaskViewServiceClient.java     # bind 服务 + 断线重连骨架 + linkToDeath
├── res/                        # 布局/中文文案/系统主题
├── systemui/                   # SystemUI 补丁镜像 + 放置说明（与树内文件一致）
│   └── README.md               #   每个文件拷贝到树内哪个路径、手工注册点清单
└── docs/
    ├── design.md               # 路径 A 设计论证 + 嵌入/输入/生命周期流程
    └── integration.md          # ROM 集成指南（源码树落点全清单）
```

## UI 与交互策略（v1）

- 左侧 280dp 应用列表（图标 + 名称），右侧内容区为主/副两个槽位：
  - 双槽都空 → 内容区显示提示文本；
  - 只有一个槽占用 → 该槽占满内容区；
  - 两个都占用 → 等分（weight 1:1）。
- 点击应用图标的槽位策略（v1）：
  1. 该 app 已在主或副槽 → `host.showEmbeddedTask` 置前（当前服务端为 no-op，槽位不重叠，
     嵌入式 task 本来就在自己槽内最前）；
  2. 主槽空 → 嵌入主槽；
  3. 副槽空 → 嵌入副槽；
  4. 都满 → 替换主槽（旧 TaskView release，task 随之从 WM 移除），副槽不动。
- 左栏对"主槽当前 app"做选中高亮。
- task 退出（用户在被嵌入 app 内按返回直至 finish，或进程死亡）→ `onTaskVanished` →
  槽位置空并刷新布局。

## TaskView 嵌入路径 A（一段话）

Android 16 已移除 ActivityView，而 `TaskView`（`com.android.wm.shell.taskview`）只能活在
WMShell 所在进程——手机上就是 SystemUI 进程。因此采用**自研精简版 AAOS 桥**（路径 A）：
SystemUI 侧新增导出服务 `CarLinkTaskViewService` 作为 host，内部用 `TaskViewFactory` 创建
`TaskView` 并把其 `TaskViewBase` 换成跨进程桥；launcher 侧的 `CarLinkTaskView`（SurfaceView）
在 `surfaceCreated` 里把 `SurfaceControl` 的拷贝（可 Parcel）经 Binder 交给 host，task leash
由 shell 侧 reparent 到该 surface 下。因为 leash 已经挂进 launcher 的 surface 层级，
InputDispatcher 直接路由触摸，**输入零转发**；宿主窗口只需
`FLAG_NOT_TOUCH_MODAL` + 客户端在 touchable region 上打洞。详见 [docs/design.md](docs/design.md)。

## 集成方法（摘要）

- 本仓库拷贝为 `vendor/carlink/launcher/`，`vendor/carlink/carlink.mk` 的
  `PRODUCT_PACKAGES` 追加 `CarLinkLauncher`，privapp 白名单放行 `QUERY_ALL_PACKAGES`；
- SystemUI 补丁（`systemui/` 镜像）已落在 `frameworks/base/packages/SystemUI/`
  （aidl + `com.android.systemui.carlink` 三文件 + manifest / dagger 两处注册点）。

完整落点清单与编译、真机验证步骤见 [docs/integration.md](docs/integration.md)。

## 文档索引

- [docs/design.md](docs/design.md) — 设计论证：路径 A/B/C 对比、嵌入链路全流程、BAL 要点、双槽模型
- [docs/integration.md](docs/integration.md) — 集成指南：源码树落点、注册点、编译与真机验证
- [systemui/README.md](systemui/README.md) — SystemUI 补丁镜像与手工注册点清单

## License

Apache-2.0。部分文件派生自 AAOS（AOSP），派生关系与署名见 [LICENSE](LICENSE) 末尾的
ATTRIBUTION NOTICE，各文件头部保留了原始版权头。
