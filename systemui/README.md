# SystemUI 补丁镜像

本目录是 SystemUI 侧补丁的**镜像副本**，与 LineageOS 源码树（`/root/android/lineage`）内已落地的文件保持一致。补丁已直接落在树内；本目录用于仓库留存、评审与在其他源码树上复用时对照拷贝。

## 文件放置对照

| 本目录文件 | 树内路径 |
| --- | --- |
| `aidl/com/carlink/taskview/ICarLinkTaskViewService.aidl` | `frameworks/base/packages/SystemUI/src/com/carlink/taskview/ICarLinkTaskViewService.aidl` |
| `aidl/com/carlink/taskview/ICarLinkTaskViewHost.aidl` | `frameworks/base/packages/SystemUI/src/com/carlink/taskview/ICarLinkTaskViewHost.aidl` |
| `aidl/com/carlink/taskview/ICarLinkTaskViewClient.aidl` | `frameworks/base/packages/SystemUI/src/com/carlink/taskview/ICarLinkTaskViewClient.aidl` |
| `src/com/android/systemui/carlink/CarLinkTaskViewService.java` | `frameworks/base/packages/SystemUI/src/com/android/systemui/carlink/CarLinkTaskViewService.java` |
| `src/com/android/systemui/carlink/CarLinkTaskViewHost.java` | `frameworks/base/packages/SystemUI/src/com/android/systemui/carlink/CarLinkTaskViewHost.java` |
| `src/com/android/systemui/carlink/CarLinkTaskViewServerImpl.java` | `frameworks/base/packages/SystemUI/src/com/android/systemui/carlink/CarLinkTaskViewServerImpl.java` |

说明：AIDL 三件套与仓库根目录 `aidl/` 下的文件**内容完全相同**（launcher 与 SystemUI 各自编译同一份契约，包名 `com.carlink.taskview` 保持一致才能跨进程互通）。

## 需要手工加的注册点（树内现有文件的追加式改动）

1. `frameworks/base/packages/SystemUI/AndroidManifest.xml`
   追加导出 Service 声明（已带 `// CarLink:` 风格注释）：

   ```xml
   <service android:name="com.android.systemui.carlink.CarLinkTaskViewService"
       android:exported="true"
       android:permission="com.carlink.permission.MANAGE_TASK_VIEW">
       <intent-filter>
           <action android:name="com.carlink.taskview.action.BIND_TASK_VIEW_SERVICE" />
       </intent-filter>
   </service>
   ```

2. `frameworks/base/packages/SystemUI/src/com/android/systemui/dagger/ReferenceSystemUIModule.java`
   - import `com.android.systemui.carlink.CarLinkTaskViewHost;`
   - `@Module(includes = {...})` 列表追加 `CarLinkTaskViewHost.StartableModule.class,`

   注：任务书原定注册到 `SystemUICoreStartableModule.kt`，但该文件在树内已被标记废弃
   （"DEPRECATED: DO NOT ADD THINGS TO THIS FILE"，b/427499553），故按现行惯例改用
   feature module（`CarLinkTaskViewHost.StartableModule`）+ `ReferenceSystemUIModule` includes。

3. `frameworks/base/packages/SystemUI/Android.bp` —— **无需修改**。
   `SystemUI-core-srcs` filegroup 已含 `src/**/I*.aidl` glob，三份 AIDL 放到
   `src/com/carlink/taskview/` 后自动参与编译（与 `IScreenshotProxy.aidl` 等现有 AIDL 同一模式）。

## 同步纪律

修改树内补丁后，请同步更新本目录镜像，保持二者一致（可用 `diff -r` 核对）。
