# 客厅桌面 · Living Room Launcher

一个以 Apple TV 系统主屏为视觉参考的原生 Android 电视桌面：应用横卡、遥控焦点、玻璃控制中心、可更新背景及局域网更新服务。Java Views + Android SDK 直接构建，不依赖 Gradle 或第三方 Android 库。

这是从 TCL 实践工程整理出的**独立公开参考版**，包含实际实现，不包含原家庭的网络配置、签名密钥、诊断数据、厂商 APK 或安装策略修改工具。**小米电视需要重新验证默认 HOME、物理 Home 键及观看记录接口；这里不承诺通用替换所有厂商首页。**

公开版包名为 `io.github.elliclee.livingroom`，版本从 `1.0.0/code1` 开始，和原私人版本独立。它不会直接覆盖原私人 APK；每位开发者首次构建生成自己的本地签名。

## 给朋友的最快开始方式

把仓库地址交给本机 Codex，并发送：

> 请克隆并阅读本仓库、AGENTS.md 和 docs/小米电视-Codex交接指南.md。基于现有代码适配我的小米电视，先确认型号、系统/API、电视地址及安装通道，再验证安装和真正默认首页，随后调整 UI、背景与更新。不要照搬 TCL 接口或承诺尚未验证的兼容性。直接推进可执行的测试和修改。

完整交接：[小米电视 Codex 指南](docs/小米电视-Codex交接指南.md)。

## 已有能力

- 原生遥控器主屏、Top Shelf 区域、六列应用卡面及可展开栅格。
- 宽幅 banner、自适应/透明/圆形图标适配，统一卡面和焦点效果。
- 应用启动、手动排序/隐藏、常用优先排序，偏好保存在本机。
- 控制中心、系统设置与原首页入口、系统 HOME 角色请求。
- 原创离线海岸背景、每日风景、自定义 HTTP(S) 图片或 JSON；网络失败回到可用背景。
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

输出在 `dist/LivingRoom-1.0.0.apk`，附带 `.sha256` 和 `build-report.json`。通过电视支持的途径安装；没有连接/授权时，Codex 不能仅凭 IP 就控制电视。

## 检查更新

电脑发布并运行服务：

```sh
python3 update_server.py publish dist/LivingRoom-1.0.0.apk --notes '首次构建'
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
| `BackgroundRepository.java`、`RecentViewing.java` | 图片/接口缓存及现有 TCL 只读适配 |
| `DeviceNavigation.java` | 本机系统设置、记录原 HOME 与恢复入口 |
| `UpdateFlow.java`、`UpdateResultReceiver.java` | 私有下载、包校验、用户确认及回调 |
| `build.py`、`update_server.py` | 构建/本地签名、更新包发布与服务 |

`DeviceNavigation` 会在切换前记录原 HOME；原入口不明确、有多个候选时打开系统选择设置，不猜测小米包名。厂商可能限制这些公开入口，需要在设备上核对。

## 验证

```sh
python3 -W error::ResourceWarning -m unittest discover -s tests -v
```

无需 SDK 的测试覆盖 HTTP 鉴权、路由边界、清单切换、配置复用和无效地址。先成功构建后，还会用真实 SDK/签名工具验证正确发布，以及错误签名、其他包名、缺少 HOME 被拒绝，失败发布不改变当前清单。未构建时该组会明确跳过。

实际公开版检查与边界见 [验证记录](docs/验证记录.md)。模拟器、静态分析和 TCL 经验不能代替朋友的小米实机结果。

## 设计与素材

见 [设计与适配](docs/设计与适配.md)、[素材说明](docs/素材说明.md)。仓库不附 Apple 官方截图或第三方应用品牌图标；应用运行时读取本机已安装应用的图标。每日风景由电视直接访问 Bing，自定义背景使用用户配置的地址；这些网络图片的权利不随本仓库 MIT 许可转移。

源码采用 [MIT License](LICENSE)。
