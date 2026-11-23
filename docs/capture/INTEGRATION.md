# 网络抓包集成（2026-11-23）

本模块在工具合集主仓库维护，随宿主统一构建与发布。

## 使用入口

工具合集 → 工具页面 → 网络抓包。进入后为工作台、响应、设置三个页面；点击开始才启动抓包。
HTTP 代理与 VPN IPv4 两种模式共用内置 mitmproxy、响应规则和日志。HTTPS/HTTP3 解密需要设备信任该模块的 CA。
抓包限定 Android 10+、ARM64；工具合集其他功能保留 Android 8+ 的安装与代码门槛，旧系统运行回归仍需实机/模拟器验证。不能同时运行独立抓包 App 和合集抓包。

## 代码组织

- `capture/`：来自 jd-crawl Android 0.3.2，改为 Android Library，包含 UI、Shizuku 服务、DNS/通信桥、Python 内核和 VPN。
- `capture-core/`：证书、协议、响应规则与单元测试。
- `app/`：工具合集宿主，首页入口与设备兼容判断。
- `capture/UPSTREAM.json`：迁入时原文件 SHA256；后续集成修改以当前源码为准。
- 原 Mac 控制台和发送器不作为 Android 后台任务加载。

## 集成差异

保留包名 `cn.local.toolkit`、原启动行为与签名配置。抓包 Activity 非导出，独立主题；Shizuku 统一 13.1.5，只合并一个 provider。
抓包通知使用 2101，避免覆盖模拟定位的通知 1；两个 Shizuku 用户服务使用不同进程后缀。
宿主、抓包 Activity 和服务共用兼容门禁；Manifest 的 `overrideLibrary` 仅允许保留宿主 minSdk 26，不表示抓包内核支持 API 26。
WireGuard/抓包代码依赖 Java 17；宿主和库均开启核心库 desugaring。只排除重复的 OSGi 描述文件，不排除加密实现或许可证。
HTTP 明文策略继续采用宿主的禁止全局明文设置；代理内核和通信桥使用 loopback socket。
运行时按宿主 UID 隔离，CA/日志存入宿主私有目录。不会自动读取独立 App 的 CA、日志或授权；已有用户 CA 仅有公钥证书不足以复用签发能力，私钥未迁入本仓库。
不改变原请求提交保护：结算最终提交记录后本地 409 阻断；没有加入自动/定时下单。

## 构建与验证

JDK 17、SDK 35、Gradle 8.10.2：

```sh
python3 -m unittest discover -s capture/scripts -p 'test_*.py'
./gradlew :capture-core:test :capture:testDebugUnitTest :app:testDebugUnitTest :capture:lintRelease :app:lintRelease :app:assembleDebug :app:assembleRelease
python3 capture/scripts/verify-apk.py
```

`capture/src/main/assets/embedded/runtime.bin` 为固定校验的 gzip/tar 运行时，SHA256：
`19c0c8ff7fd8e59cdcd29d9f338f504dc67275809496619c2915205894dd49b5`（37,587,384 字节）。
合集发行包仅剔除了原运行时中 Tornado 的公开测试私钥 `site/tornado/test/test.key`，不修改运行时代码。原始包 SHA256 `6f05361208a0e402ced5665d87ec10b115dd52b4275db7e1fc5763cc37521be3` 保留在迁入溯源记录；`capture/scripts/repack-runtime.py` 可从原始包产生剔除版本。原独立项目未改动。
构建前校验完整哈希，并检查私钥文件/完整私钥 PEM；缺失、损坏或包含私钥直接失败。开发机生成的 `__pycache__` 不打包。
使用 `.bin` 扩展名防止 Gradle 自动展开 `.gz` 资产并改名。构建后运行 `python3 capture/scripts/verify-apk.py` 校验 APK 内真实资产字节，不能仅凭源码目录有文件就判定内核已打包。
独立 APK 的历史实测不能视为合集版验收。当前版本的验证范围见 [发行说明](../releases/20261123-stability.md)。设备日志、配置备份和私有分析材料不随仓库发布。

## 待真机验收

HTTP 本机自检、管理入口与安全恢复已完成设备检查。VPN/DNS/HTTP3 合集版链路、模拟定位/无障碍与抓包同时运行、强停恢复及长时后台仍需进一步验收。
只使用专用测试接口。IPv6、QUIC v2 尚未实现。
