# 客厅桌面 · Living Room Launcher

一个以 Apple TV 系统主屏为视觉参考的原生 Android 电视桌面：应用横卡、遥控焦点、玻璃控制中心、可更新背景及局域网更新服务。Java Views + Android SDK 直接构建，不依赖 Gradle 或第三方 Android 库。

这是从 TCL 实践工程整理出的**独立公开参考版**，包含实际实现，不包含原家庭的网络配置、签名密钥、诊断数据、厂商 APK 或安装策略修改工具。**小米电视需要重新验证默认 HOME、物理 Home 键及观看记录接口；这里不承诺通用替换所有厂商首页。**

公开版包名为 `io.github.elliclee.livingroom`，版本从 `1.0.0/code1` 开始，当前 `1.2.1/code6`，和原私人版本独立。它不会直接覆盖原私人 APK；每位开发者首次构建生成自己的本地签名。

## 给朋友的最快开始方式

把仓库地址交给本机 Codex，并发送：

> 请克隆并阅读本仓库、AGENTS.md 和 docs/小米电视-Codex交接指南.md。基于现有代码适配我的小米电视，先确认型号、系统/API、电视地址及安装通道，再验证安装和真正默认首页，随后调整 UI、背景与更新。不要照搬 TCL 接口或承诺尚未验证的兼容性。直接推进可执行的测试和修改。

完整交接：[小米电视 Codex 指南](docs/小米电视-Codex交接指南.md)。

## 已有能力

- 原生遥控器主屏、Top Shelf 区域、六列应用卡面及可展开栅格。
- 宽幅 banner、自适应/透明/圆形图标适配，统一卡面和焦点效果。
- 应用启动、手动排序/隐藏、常用优先排序，偏好保存在本机。
- 控制中心、系统设置与原首页入口、系统 HOME 角色请求。
- 原创海岸、每日风景、西方艺术、NASA、摄影集、自定义 HTTP(S) 图片或 JSON；公开版内置12幅艺术画作目录及5张NASA摄影目录，10张图片随应用内置。摄影集使用开发者自行导入的照片。
- 背景来源支持多选：点击勾选/取消，立即保存且列表保持打开；每30分钟或手动更新在已选来源之间轮换，左右浏览当前已选图库。
- 全屏浏览采用画廊式署名：大标题、较小的作者/日期、细小的来源/使用规范，无可见按键提示。
- 从首排按上浏览背景，左右切换前后图片，按下/返回恢复应用焦点；已选图库及多来源在主屏可见期间每 30 分钟轮换。
- TCL 观看记录的**只读适配示例**；小米及其他设备没有已验证的观看记录适配，无权限/无数据时正常显示其他背景。只能打开对应视频应用，不声称支持指定集数续播。
- 电视检查/下载更新、摘要/包名/版本/签名验证、系统确认安装；电脑端固定版本清单和不可变包地址。

## 构建

需要 **Python 3.11+、JDK 17、Android SDK Platform 35、Build Tools 35.0.0**。脚本使用已有工具，不自动下载安装。应用声明最低 API24/Android7；公开副本尚未在这些旧版本或小米实机验证。

可通过 `JAVA_HOME`、`ANDROID_SDK_ROOT`/`ANDROID_HOME` 配置已有工具；需要覆盖时使用 `LIVINGROOM_JDK`、`LIVINGROOM_SDK`。macOS 会尝试系统 JDK17 及标准 SDK 路径；Windows 路径/脚本名已适配，但尚未实际验证 Windows 构建。

在仓库目录执行，**将占位符替换为你自己电脑的局域网 IPv4 地址**：

```sh
python3 update_server.py init --host YOUR_COMPUTER_LAN_IP
python3 build.py
```

默认更新端口 `57816`，可在首次 `init` 时通过 `--port` 指定。构建不启动服务。首次构建自动生成本项目的独立签名；配置与授权写入 `.build/`，不会提交 Git。

输出在 `dist/LivingRoom-1.2.1.apk`，附带 `.sha256` 和 `build-report.json`。通过电视支持的途径安装；没有连接/授权时，Codex 不能仅凭 IP 就控制电视。

## 检查更新

电脑发布并运行服务：

```sh
python3 update_server.py publish dist/LivingRoom-1.2.1.apk --notes '首次构建'
python3 update_server.py serve
```

电视进入右上角控制中心 → 检查更新。电脑需要保持开机、服务运行并与电视网络互通。下载成功后由用户在电视系统弹窗确认，普通应用不保证静默安装。

后续修改 `AndroidManifest.xml` 的 `versionName` 和递增的 `versionCode`，重新构建并 `publish`。正在运行的服务会读取新清单，无需另启动实例。

不要删除或更换 `.build/signing.p12`、`signing-password`、`signing-identity.json`、`update-server-state.json`；自行安全备份。它们分别维持你自己安装版本的升级签名和更新授权。另一位开发者应自行初始化，不要分享这些文件。更新授权会编入你自己的 APK，仓库不提供预编译 APK。

电脑 IP 变化时，电视可修改电脑 IP；电脑服务的绑定地址也应同步调整本地配置中的 `host`，保持端口/令牌不变。初始化命令会拒绝意外重置已有配置。

## 代码导航

| 文件 | 职责 |
| --- | --- |
| `src/io/github/elliclee/livingroom/HomeActivity.java` | 布局、焦点、排序、控制中心与更新 UI |
| `TvAppearance.java`、`AppArtwork.java` | 材质、字体/形状、图标卡面处理 |
| `AppRanking.java` | 本桌面启动次数和应用顺序 |
| `BackgroundRepository.java`、`GalleryCollection.java`、`RecentViewing.java` | 图片/接口缓存及现有 TCL 只读适配 |
| `DeviceNavigation.java` | 本机系统设置、记录原 HOME 与恢复入口 |
| `UpdateFlow.java`、`UpdateResultReceiver.java` | 私有下载、包校验、用户确认及回调 |
| `build.py`、`update_server.py` | 构建/本地签名、更新包发布与服务 |

`DeviceNavigation` 会在切换前记录原 HOME；原入口不明确、有多个候选时打开系统选择设置，不猜测小米包名。厂商可能限制这些公开入口，需要在设备上核对。

## 验证

```sh
python3 -W error::ResourceWarning -m unittest discover -s tests -v
```

无需 SDK 的测试覆盖图库许可/来源/素材摘要，以及 HTTP 鉴权、路由边界、清单切换、配置复用和无效地址。先成功构建后，还会用真实 SDK/签名工具验证正确发布，以及错误签名、其他包名、缺少 HOME 被拒绝，失败发布不改变当前清单。未构建时该组会明确跳过。

在明确选择的隔离模拟器上，可另外运行 `python3 tools/test_android_gallery.py --serial emulator-具体端口`，验证真实 JPEG 解码和背景加载；加 `--suite sources` 验证多选，`--suite controls` 验证圆角/裁剪。该工具不创建/关闭模拟器，不允许实际电视，且会调整测试包的背景缓存与偏好。

实际公开版检查与边界见 [验证记录](docs/验证记录.md)。模拟器、静态分析和 TCL 经验不能代替朋友的小米实机结果。

## 设计与素材

见 [设计与适配](docs/设计与适配.md)、[素材说明](docs/素材说明.md)。仓库不附 Apple 官方截图或第三方应用品牌图标；应用运行时读取本机已安装应用的图标。每日风景由电视直接访问 Bing，自定义背景使用用户配置的地址；这些网络图片的权利不随本仓库 MIT 许可转移。

艺术与 NASA 来源、维护命令和遥控逻辑见 [艺术背景设计](docs/艺术背景设计.md)。素材单独注明授权，未改变源码许可。

源码采用 [MIT License](LICENSE)。

## 添加自己的摄影集

需要已有 `ffmpeg` / `ffprobe`，脚本不安装工具。在你自己的仓库执行：

```sh
python3 tools/import_photography.py '你的照片文件夹'
# 可选：JSON键为相对文件名，值为摄影作品显示名。
python3 tools/import_photography.py '你的照片文件夹' --titles '作品显示名.json'
python3 build.py
```

生成的 `assets/photography/` 被 Git 忽略，仅进入你自己的 APK；原照片不改。未导入照片的公开版显示摄影集尚未配置。素材不因导入获得 CC0/MIT 许可。打包后在背景来源勾选“摄影集”，可与艺术、NASA等共同轮换。详见艺术背景设计说明。
