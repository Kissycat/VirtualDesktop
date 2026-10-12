# 屏幕分身（多虚拟显示控制）

本应用是一个基于 **Shizuku** 和 **scrcpy** 技术开发的 Android 辅助工具，用于在手机上创建多个“虚拟屏幕”，并能在这些独立的屏幕中运行不同的应用。主要适用于多开挂机、多任务并行等场景。

## 主要功能

- **创建虚拟屏幕**：支持自定义分辨率与屏幕密度（DPI）创建虚拟屏幕。
- **独立运行应用**：从应用列表中选择任意应用，让其在指定的虚拟屏幕中单独运行。
- **预览与控制**：支持实时预览虚拟屏幕的画面，并可以直接通过触摸进行交互与控制。

## 使用前提

1. **安装并启动 Shizuku**：本应用依赖 [Shizuku](https://shizuku.rikka.app/) 服务。请先在您的安卓设备上安装并激活 Shizuku（可通过无线调试或 Root 激活）。
2. **授予权限**：首次打开本应用时，请按照提示授予 Shizuku 访问权限。

## 快速上手

1. **启动服务**：确认 Shizuku 服务已启动(现已支持root拉起)，打开“屏幕分身”应用。
2. **新建屏幕**：输入您想要的宽度、高度和 DPI（例如 1080, 2400, 440），点击创建。
3. **管理与使用**：
   - **Play**：打开预览窗口，支持触摸操作。
   - **Launch**：选择想要在该屏幕中启动的应用。
   - **Delete**：删除/释放该虚拟屏幕。

## 常见问题

- **创建屏幕时出现 `packageName must match the calling uid`？**
  请确保以 **adb / shell 身份**激活 Shizuku，而非以 Root 身份激活。
  部分设备的 [`DisplayManagerService`](https://github.com/cn00/android/blob/master/services/core/java/com/android/server/display/DisplayManagerService.java#L1532) 会校验调用者的 UID 与 packageName 是否匹配；而scrcpy 通过将 `PACKAGE_NAME` 固定为 `"com.android.shell"`，以 Root 身份运行时 会导致二者不匹配从而抛出 `SecurityException`。（ 本项目暂未对此进行修复。）

- 端口监听和连接其他设备有bug，基本用不了，最近没空，过段时间再修，视频传输也存在一定问题。应该需要重新调整出一个清晰的架构。

- **其他问题 **
  有其他问题或建议，欢迎在 [GitHub 仓库](https://github.com/Ynkcc/VirtualDisplay/issues) 提交问题。
  消极维护，有能力的话建议自行处理。

## 致谢

本项目的实现离不开以下开源项目的支持：
- [Shizuku](https://github.com/RikkaApps/Shizuku) - 提供免 Root/特权 API 访问能力。
- [scrcpy](https://github.com/Genymobile/scrcpy) - 提供高效的屏幕控制与输入注入逻辑。

## 开源协议

本项目基于 [Apache License 2.0](LICENSE) 协议开源。
## Browser WebRTC output

After the app starts, the control API listens on `0.0.0.0:18081`. The H.264 bridge is started on demand (default `0.0.0.0:18080`).

1. Start a video stream for the target display in the app.
2. Start H.264 output:
   `curl -X POST http://<android-ip>:18081/api/webrtc/start -H 'Content-Type: application/json' -d '{"displayId":3,"bindHost":"0.0.0.0","port":18080}'`
3. Run `webrtc-gateway` with `ANDROID_H264=<android-ip>:18080`.
4. Open `http://<gateway-ip>:19000/` in Chrome/Edge.
5. Stop output when no browser needs the stream:
   `curl -X POST http://<android-ip>:18081/api/webrtc/stop`

This path reuses the existing H.264 encoder. The Android side does not add a second video encoder.

## 桌面模式

控制台新增“桌面模式”，桌面本身创建独立的 `1920×1080 / 160 DPI` 横向虚拟显示器。桌面里的每一个“小窗”都再创建一个独立的 VirtualDisplay，并把该 VirtualDisplay 的画面呈现在目标桌面显示器上的 `TYPE_APPLICATION_OVERLAY` + `TextureView` 中；Overlay 只负责窗口外框、标题栏、Pin、缩放边角和底部手势 Pill，应用本身始终运行在自己的 VirtualDisplay 中，不使用 Android Task Freeform/windowing mode，也不调用系统侧 LMOFreeform 服务。

小窗生命周期与 LMOFreeform 一致：先挂载 Overlay，等待 TextureView 的 `SurfaceTexture` 可用，再创建 VirtualDisplay，并通过 `ActivityOptions.setLaunchDisplayId()` 把应用启动到该显示器。移动窗口只修改 Overlay 的 WindowManager.LayoutParams；缩放只同步 Overlay 内容尺寸和 VirtualDisplay 尺寸；Pin/HangUp 只改变 Overlay 的显示形态。每个小窗拥有自己的 displayId，因此同一应用可以同时打开多个独立实例。

桌面模式的输入同样以 VirtualDisplay 为目标：TextureView 接收触摸/鼠标事件，转换后通过对应 displayId 的 ROLE_CONTROL 通道注入到该 VirtualDisplay，避免把输入错误地发送到物理主屏或另一个小窗。

桌面模式进入操控界面后仍保持原有 WebRTC/H.264 输出链路；当 WebRTC 输出状态变为运行时，本机可关闭对应画面解码，仅保留触控/指针控制。

### Freeform chrome provenance

The client-side desktop window chrome in `app/src/main/java/com/ynk/virtualdisplay/ui/desktop/FreeformOverlayDecoration.kt` is a port of the LMOFreeform interaction model and visual structure. The original LMOFreeform project is licensed under the GNU General Public License v3 or later; the corresponding license text is included as `LICENSE-LMOFREEFORM-GPL.txt`. Review the combined licensing requirements before redistributing a build containing this code.


## 中文与 emoji 输入说明

普通英文/ASCII 输入继续通过 scrcpy 键盘事件路径。Android 的 `KeyCharacterMap` 无法把任意 CJK 字符或 emoji 表示成普通物理按键，因此非 ASCII 的已提交文本改走可选的 `RemoteTextInputAccessibilityService`：它只在收到明确的文本插入请求时，查找目标虚拟显示器当前聚焦的可编辑节点，并在当前选区处插入文字；不读取/记录其他文本事件，也不使用系统剪贴板。

首次输入中文/emoji 前，请在系统“设置 → 无障碍”中手动启用“屏幕分身 Unicode 文字输入辅助”。如果目标 APP 没有向无障碍公开可编辑节点，服务会记录失败并提示，而不会假装已经输入成功。窗口的 `adjustNothing` 也已改为 Activity 创建前的 manifest 配置，避免首次打开输入法时才修改窗口软输入模式。
