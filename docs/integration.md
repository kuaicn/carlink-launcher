# ROM 集成指南

目标源码树：LineageOS 23.2 / Android 16（`/root/android/lineage`），目标机小米 10（umi）。
本文列出全部落点；**当前这些改动已经落在树内**，本文用于评审、复用与回迁其他源码树。

## 1. Launcher 模块

仓库内容（排除 `.git`、`systemui/` 镜像目录）拷贝为：

```
vendor/carlink/launcher/
├── Android.bp                  # android_app: CarLinkLauncher
├── AndroidManifest.xml
├── aidl/com/carlink/taskview/  # 三份 AIDL
├── src/com/carlink/launcher/   # 5 个 Java 文件
├── res/                        # 布局/文案/主题
├── README.md / docs/ / LICENSE
```

`vendor/carlink/carlink.mk`：

```makefile
PRODUCT_PACKAGES += \
    CarLinkInterconnect \
    CarLinkLauncher \
    privapp-permissions-carlink.xml
```

`vendor/carlink/config/privapp-permissions-carlink.xml` 追加白名单块：

```xml
<privapp-permissions package="com.carlink.launcher">
    <permission name="android.permission.QUERY_ALL_PACKAGES"/>
</privapp-permissions>
```

## 2. SystemUI 补丁落点全清单

新增文件（均为纯新增，不动现有逻辑）：

| 文件 | 树内路径 |
| --- | --- |
| ICarLinkTaskViewService.aidl | `frameworks/base/packages/SystemUI/src/com/carlink/taskview/` |
| ICarLinkTaskViewHost.aidl | 同上 |
| ICarLinkTaskViewClient.aidl | 同上 |
| CarLinkTaskViewService.java | `frameworks/base/packages/SystemUI/src/com/android/systemui/carlink/` |
| CarLinkTaskViewHost.java | 同上 |
| CarLinkTaskViewServerImpl.java | 同上 |

现有文件的追加式修改（每处带 `// CarLink:` 或 `<!-- CarLink: -->` 注释）：

1. `frameworks/base/packages/SystemUI/AndroidManifest.xml`
   追加 `com.android.systemui.carlink.CarLinkTaskViewService` 导出声明
   （`android:permission="com.carlink.permission.MANAGE_TASK_VIEW"`，intent-filter action
   `com.carlink.taskview.action.BIND_TASK_VIEW_SERVICE`）。
2. `frameworks/base/packages/SystemUI/src/com/android/systemui/dagger/ReferenceSystemUIModule.java`
   - import `com.android.systemui.carlink.CarLinkTaskViewHost;`
   - `@Module(includes=...)` 追加 `CarLinkTaskViewHost.StartableModule.class,`
   - 注：未使用任务书最初指定的 `SystemUICoreStartableModule.kt`，因为该文件在树内已标记
     废弃（"DO NOT ADD THINGS TO THIS FILE"，b/427499553）；feature module + includes 是现行惯例。
3. `frameworks/base/packages/SystemUI/Android.bp` —— **无需修改**：`SystemUI-core-srcs`
   filegroup 的 `src/**/I*.aidl` glob 自动覆盖新 AIDL（与 IScreenshotProxy.aidl 等同一模式）。

补丁的仓库镜像与对照表见 [../systemui/README.md](../systemui/README.md)。

## 3. 编译说明（Soong）

不要全量编译 ROM。在源码树顶层 `source build/envsetup.sh && lunch` 之后按模块构建：

- launcher 模块：`m CarLinkLauncher`（Soong 解析 `vendor/carlink/launcher/Android.bp`）
- SystemUI：`m SystemUI-core`（增量编译 aidl + carlink 三文件 + dagger/manifest 合并）
- 需要整机验证时再 `m` 出 system image 或用 `adb sync` 类流程推送
  `/system_ext/priv-app/CarLinkLauncher` 与 SystemUI 产物（具体分区以设备配置为准）。

常见问题：

- `com.carlink.permission.MANAGE_TASK_VIEW` 是 launcher 定义、SystemUI 引用的签名权限，
  两端都随 platform 签名编译，无额外声明点；
- 若 aidl 报 parcelable 未声明：`RunningTaskInfo`/`SurfaceControl` 等在 framework.aidl 中，
  `platform_apis: true` / SystemUI-core 的编译路径已自带，无需手工 import 声明文件。

## 4. 真机验证清单

前置：互联服务已能创建虚拟屏并把本桌面拉起（`getLaunchIntentForPackage("com.carlink.launcher")`
+ `setLaunchDisplayId`）。

1. `adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN
   -c android.intent.category.LAUNCHER com.carlink.launcher` 能找到 LauncherActivity。
2. 桌面在虚拟屏上显示：左栏列出应用（图标 + 名称），内容区显示提示文本。
3. 点击应用 → 主槽嵌入：画面出现在主槽，`adb shell dumpsys activity lru` /
   `dumpsys SurfaceFlinger` 可见 task 落在虚拟 displayId 上。
4. 触摸：直接在被嵌入 app 上滑动/点击，事件直达（不经过 launcher 转发）；
   左栏列表仍可正常点击（touchable region 打洞正确）。
5. 再点第二个应用 → 副槽嵌入，主/副等分。（已知限制：主槽 task 画面尺寸不即时跟随
   槽位收窄，见 design.md 第 6 节。）
6. 再点第三个应用 → 主槽替换，副槽保持。
7. 在被嵌入 app 内一路返回退出 → 槽位自动清空，提示文本恢复。
8. `adb shell killall com.android.systemui` 模拟 SystemUI 死亡 → 槽位清空、日志出现
   重连；SystemUI 重启后再次点击可正常嵌入。
9. 日志：`adb logcat -s CarLinkLauncher CarLinkTaskViewHost CarLinkTaskViewServerImpl
   CarLinkTaskViewService` 无 SecurityException / RemoteException 刷屏。
