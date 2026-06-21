# CMSS-Box

CMSS-Box 是一个基于 **NekoBox for Android** 定制的 Android 客户端方案，配套 **Cloudflare Worker** 服务端，用于实现远程配置分发、邀请码激活和设备模板管理。

## 功能概览

* Android 客户端远程拉取配置
* 邀请码激活设备
* 按设备绑定不同配置模板
* 后台管理模板、邀请码和设备
* Cloudflare Worker + D1 + KV 服务端
* 支持二次定制客户端和服务端逻辑

## 项目结构

```text
app/                           Android 客户端
cloudflare/sub-json/            Cloudflare Worker 服务端
cloudflare/sub-json/migrations/ D1 数据库迁移文件
```

## 服务端接口

```text
/app-config.json        客户端远程配置入口
/api/v1/activate        设备激活
/api/v1/config          获取设备配置
/api/v1/subscription    获取最终订阅
/admin/*                管理后台
```

## 一、部署 Cloudflare Worker 服务端

### 1. 准备 Cloudflare 资源

需要准备：

* 1 个 Worker
* 1 个 D1 数据库
* 1 个 KV Namespace

推荐命名：

```text
Worker: sub-json
D1:     cmss-device-config
KV:     cmss-config
```

### 2. 安装依赖

进入 Worker 目录：

```bash
cd cloudflare/sub-json
npm install
```

### 3. 修改 `wrangler.jsonc`

打开：

```text
cloudflare/sub-json/wrangler.jsonc
```

替换为你自己的 Cloudflare 资源 ID：

```text
YOUR_KV_NAMESPACE_ID
YOUR_D1_DATABASE_ID
```

默认绑定名保持不变：

```text
DB
CONFIG_KV
```

### 4. 设置后台密钥

需要设置两个 Worker secrets：

```bash
npx wrangler secret put TOTP_SECRET_BASE32
npx wrangler secret put ADMIN_SESSION_SECRET
```

说明：

```text
TOTP_SECRET_BASE32     后台登录用的 TOTP Base32 密钥
ADMIN_SESSION_SECRET   后台 session 签名密钥
```

后台登录使用六位动态验证码。

### 5. 初始化 D1 数据库

```bash
npx wrangler d1 migrations apply cmss-device-config --remote
```

如果你的 D1 数据库名称不是 `cmss-device-config`，请替换为自己的数据库名称。

### 6. 部署 Worker

```bash
npx wrangler deploy
```

部署完成后访问：

```text
https://你的-worker域名/admin/login
```

## 二、初始化后台

登录后台后，按顺序完成：

1. 创建配置模板
2. 生成邀请码
3. 在 Android 客户端输入邀请码激活
4. 在后台查看设备绑定状态
5. 根据需要为设备切换模板

模板切换不会实时推送到正在运行中的客户端。客户端需要在下次启动或手动更新时重新拉取配置。

## 三、配置 Android 客户端

在项目根目录创建或修改：

```text
local.properties
```

加入你的服务端地址：

```properties
MANAGEMENT_API_URL=https://你的-worker域名
REMOTE_CONFIG_URL=https://你的-worker域名/app-config.json
```

如果需要内置默认订阅，可以额外加入：

```properties
BUILTIN_SUB_URL=https://你的默认订阅地址
```

## 四、配置编译环境

### 1. 基础要求

需要安装：

* Android Studio
* Android SDK
* JDK 17
* Git

建议使用 Android Studio 自带的 SDK 管理器安装所需 Android SDK 和 Build Tools。

### 2. 检查 Java 版本

执行：

```bash
java -version
```

应显示 JDK 17，例如：

```text
java version "17.x.x"
```

如果系统存在多个 Java 版本，请将 `JAVA_HOME` 指向 JDK 17。

Windows 示例：

```powershell
$env:JAVA_HOME="你的 JDK 17 路径"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

macOS / Linux 示例：

```bash
export JAVA_HOME="你的 JDK 17 路径"
export PATH="$JAVA_HOME/bin:$PATH"
```

再次检查：

```bash
java -version
```

## 五、编译 Android 客户端

在项目根目录执行：

```bash
./gradlew :app:assembleDebug
```

Windows PowerShell 执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

可选构建不同版本：

```bash
./gradlew :app:assembleOssDebug
./gradlew :app:assembleFdroidDebug
./gradlew :app:assemblePlayDebug
./gradlew :app:assemblePreviewDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat :app:assembleOssDebug
.\gradlew.bat :app:assembleFdroidDebug
.\gradlew.bat :app:assemblePlayDebug
.\gradlew.bat :app:assemblePreviewDebug
```

编译产物位于：

```text
app/build/outputs/apk/
```

## 六、常见问题

### 后台提示 `TOTP未配置`

检查是否已经设置：

```bash
npx wrangler secret put TOTP_SECRET_BASE32
```

设置后重新部署 Worker：

```bash
npx wrangler deploy
```

### 设备页加载失败

优先检查：

* D1 是否完成迁移
* `wrangler.jsonc` 中的 D1 ID 是否正确
* Worker 是否已重新部署

### 模板切换后客户端没有变化

这是正常现象。模板切换只影响后续配置拉取，客户端需要下次启动或手动更新时重新获取配置。

### 提示 `MANAGEMENT_API_URL 未配置`

说明客户端编译时没有正确配置服务端地址。请检查：

```properties
MANAGEMENT_API_URL=https://你的-worker域名
```

### 编译失败或找不到 Java

检查：

```bash
java -version
```

确认当前使用的是 JDK 17。

如果不是 JDK 17，请重新设置 `JAVA_HOME`。

## 七、安全提醒

以下内容应仅保存在本地或 Cloudflare secrets 中：

* `TOTP_SECRET_BASE32`
* `ADMIN_SESSION_SECRET`
* `local.properties`
* keystore
* 证书
* 私钥
* 签名文件

不要将密钥、证书、私钥、签名文件或真实订阅地址写入公开文档、截图或可公开访问的配置文件中。

## 八、上游与许可证

本项目基于 NekoBox for Android 定制。

上游项目：

* [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
* [SagerNet/sing-box](https://github.com/SagerNet/sing-box)

本项目保留 GPL-3.0 许可证要求。分发修改版客户端时，请遵守对应开源许可证要求。
