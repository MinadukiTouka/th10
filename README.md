# 东方风神录 Android 移植（TH10 / Mountain of Faith 1.00a）

非官方、非盈利的 Android 移植，基于 [saekaze/th10-switch](https://github.com/saekaze/th10-switch)
（Switch 原生移植，游戏逻辑来自 [YomotsuHisami/th10](https://github.com/YomotsuHisami/th10)
的 clean-room C++ 重写）。上游 Switch 的说明保留在 [README-switch.md](README-switch.md)。

包名 **`com.zun.th10`**，只读取你自己合法持有的 `th10.dat` / `thbgm.dat`，
**仓库与公开 APK 均不包含任何游戏数据**。

## 特性

- **触控**：任意位置拖动移动（模拟量位移，方向跟手、斜向不减速，滑到两侧黑边也不断），
  按住即射击（松手后 180 ms 尾）、第二根手指低速、菜单滑动/点按/双指返回
- **Bomb**：点优势手一侧的黑边（右手右侧 / 左手左侧），4:3 没有余边时不启用
- **惯用手切换**：右手（Esc 左上 / Bomb 右侧）或左手（Esc 右上 / Bomb 左侧），在启动器里切换
- **Esc**：细「≡」与圆形「Esc」两个按钮都等于键盘 Esc，Back 键同 Esc；**没有退出按钮**，
  离开游戏用 Home
- **中文启动器**：数据检查、权限入口、惯用手设置均为简体中文（游戏内文本保持原版日文）
- **字体内置**：公开包装带 `msgothic.ttc`，不需要系统字体
- **60 fps 固定节奏**：在 90 Hz / 120 Hz 屏上请求并锁定 60 Hz
- 图标取自原版 `th10.exe`；存档、BGM、暂停菜单均已验证

## 安装（游戏数据手动放置）

1. 自备正版《东方风神录》1.00a，取出 `th10.dat` 与 `thbgm.dat`
2. 放到下面任一目录（文件名保持原样）：
   - **`Download/th10/`** —— 首次打开时在启动器里授予 **「所有文件访问」**
   - **`/sdcard/Android/data/com.zun.th10/files/th10/`** —— 不需要任何权限，可直接 `adb push`
3. 安装 APK（Android 8.0+，arm64-v8a）
4. 启动器显示「已找到游戏数据」后点「开始游戏」

`thbgm.dat` 缺失时游戏仍可运行，只是没有 BGM。

## 操作方式

### 触屏

| 操作 | 作用 |
| --- | --- |
| 任意位置**拖动** | 自机跟手移动（模拟量 1:1，斜向不减速） |
| 按住 | 射击（松手后 180 ms 内继续射击） |
| **第二根手指**按住 | 低速（Focus） |
| 点**优势手一侧黑边** | 放 Bomb（右手右侧 / 左手左侧） |
| **≡** 或圆形 **Esc** | 暂停 / 返回 / 取消（等同键盘 Esc） |
| 菜单里滑动 / 点按 / 双指点击 | 移动光标 / 确认 / 返回 |
| 对话 | 点按推进，按住 0.5 秒跳过 |

启动器 →「触屏惯用手」可切换 **右手**（Esc 左上、Bomb 右黑边）或 **左手**（Esc 右上、Bomb 左黑边）。

### 键盘

方向键移动，`Z` 射击，`X` Bomb，`Shift` 低速，`Esc` 暂停，`Back` 同 Esc。

## 构建

- 原生层：`ndk-build`（SDL2 2.32.10 + FreeType 2.15.0 + 游戏 + host）→ `libmain.so`
- 打包：Gradle 9.8.2 + AGP 9.4.1 + JDK 17，NDK r27b，minSdk 26 / target 36，arm64-v8a
- 这台构建机上 CMake 配置 C++ 工程会崩（`0xC0000409`），所以原生层走 ndk-build
- 版本：`1.0.2`

## 两个构建

| 构建 | 内置 | 大小 |
| --- | --- | --- |
| 公开包 `th10-1.0.2-android-apk.apk` | 只有 `msgothic.ttc` | 约 14 MB |

`-full` 包首次点「开始游戏」会把内置数据解包到应用目录（约 3 秒），之后正常走文件系统（其实就是带有游戏数据的包🤫）。

## 已验证设备

| 设备 | 系统 | 结果 |
| --- | --- | --- |
| OnePlus PJE110 | Android 16，120 Hz 面板 | 60 fps、触控与黑边拖动、左右手 Bomb、Esc/Back、存档、BGM 正常 |

## 免责声明 / Disclaimer / 免責事項

**中文**：本项目为非官方、非盈利的技术研究项目，与上海爱丽丝幻乐团及 ZUN 无任何关联。
《东方风神录 ～ Mountain of Faith.》的著作权归上海爱丽丝幻乐团及 ZUN 所有。
本项目不包含任何原版游戏数据；使用者必须合法持有原作，仅在个人、非商业环境中使用；
不得以任何形式分发原版游戏数据。

**English**: This is an unofficial, non-commercial fan project, not affiliated with Team Shanghai
Alice or ZUN. Touhou 10 and all game assets belong to Team Shanghai Alice. This repository and its
public APKs contain no game data: you must own a legitimate copy and use it for personal,
non-commercial purposes only. Never redistribute the original data files.

**日本語**：本プロジェクトは非公式・非営利の技術研究プロジェクトであり、上海アリス幻樂団および
ZUN とは一切関係ありません。『東方風神録 ～ Mountain of Faith.』の著作権は上海アリス幻樂団に
帰属します。本リポジトリと公開 APK にはゲームデータを含みません。正規品を所有する個人が
非営利で利用する場合に限ります。ゲームデータの再配布は禁止です。

## 许可与致谢

- 代码：**MIT**（游戏逻辑重写来自 YomotsuHisami / Ritosa2333 的 th10；`saekaze/th10-switch`
  的 Switch 专属文件为 CC0 1.0；本 Android 后端同样以 MIT 提供）
- 依赖：SDL2（zlib 许可）、FreeType（FTL / GPLv2 双许可）、miniaudio / stb（public domain / MIT-0）
- 游戏文本与素材：Team Shanghai Alice / ZUN
