# CMSS-Box 部署与自定义服务端指南

CMSS-Box 是一个基于 NekoBox for Android 定制的客户端方案，配套 Cloudflare Worker 服务端，实现：

- 通过邀请码激活设备
- 按设备发放和切换配置模板
- 客户端启动时强制拉取最新配置
- 后台统一管理模板、邀请码、设备绑定关系

这个仓库面向“完整部署”和“二次定制”，README 默认按从零搭建整套服务来写。

## 1. 项目结构

- Android 客户端：`app/`
- Cloudflare Worker 服务端：`cloudflare/sub-json/`
- Worker D1 迁移：`cloudflare/sub-json/migrations/`

服务端核心能力：

- `/app-config.json`：客户端远程配置入口
- `/api/v1/activate`：邀请码激活设备
- `/api/v1/config`：设备签名鉴权后获取模板配置
- `/api/v1/subscription`：设备签名鉴权后拉取最终订阅
- `/admin/*`：模板、邀请码、设备管理后台

## 2. 整体流程

完整链路如下：

1. 在 Cloudflare 部署 `sub-json` Worker，并绑定 D1 / KV / secrets
2. 在后台创建一个或多个配置模板
3. 生成邀请码，发给设备
4. Android 客户端输入邀请码激活
5. 客户端保存设备身份，后续每次启动都会向 Worker 拉取配置和订阅
6. 后台可随时给单台设备切换模板，客户端下次启动时自动应用

## 3. Cloudflare 端完整部署

### 3.1 准备资源

你需要在 Cloudflare 账号下准备：

- 1 个 Worker
- 1 个 D1 数据库
- 1 个 KV Namespace

推荐命名：

- Worker：`sub-json`
- D1：`cmss-device-config`
- KV：自定义，例如 `cmss-config`

### 3.2 安装依赖

在 `cloudflare/sub-json` 目录执行：

```powershell
npm install
```

### 3.3 修改 `wrangler.jsonc`

仓库内的 [cloudflare/sub-json/wrangler.jsonc](D:/Codex/nekobox-for-android/cloudflare/sub-json/wrangler.jsonc) 已经改成公开模板，你需要把下面两个占位值替换成你自己的真实资源 ID：

- `YOUR_KV_NAMESPACE_ID`
- `YOUR_D1_DATABASE_ID`

默认保留的配置项：

- Worker 名称：`sub-json`
- D1 绑定名：`DB`
- KV 绑定名：`CONFIG_KV`

如果你要改 Worker 名称，也要同步调整你自己的部署和访问域名。

### 3.4 配置 Worker secrets

必须设置两个 secrets：

- `TOTP_SECRET_BASE32`
- `ADMIN_SESSION_SECRET`

作用说明：

- `TOTP_SECRET_BASE32`：后台登录用的 TOTP Base32 密钥
- `ADMIN_SESSION_SECRET`：后台 session 签名密钥

示例命令：

```powershell
npx wrangler secret put TOTP_SECRET_BASE32
npx wrangler secret put ADMIN_SESSION_SECRET
```

不要把这两个值写进源码、README、截图或 Git 提交里。

### 3.5 初始化数据库

执行 D1 迁移：

```powershell
npx wrangler d1 migrations apply cmss-device-config --remote
```

如果你的 D1 名称不是 `cmss-device-config`，把命令中的数据库名替换成你自己的。

### 3.6 部署 Worker

```powershell
npx wrangler deploy
```

部署完成后，你通常会得到：

- Worker 域名，例如 `https://sub-json.<your-subdomain>.workers.dev`

后台地址：

- `https://你的-worker/admin/login`

## 4. 后台初始化与日常使用

### 4.1 登录后台

后台登录使用 TOTP 六位动态码，不是固定密码。

你需要先把 `TOTP_SECRET_BASE32` 导入自己的验证器应用，然后访问：

- `/admin/login`

### 4.2 创建模板

进入后台模板页后，为每个上游订阅创建一个模板。模板主要包含：

- 模板名称
- 上游订阅 URL
- 是否启用
- 版本号
- 客户端更新间隔

模板是设备配置分发的核心单位。

### 4.3 生成邀请码

在邀请码页中：

- 选择模板
- 设置数量
- 设置有效天数
- 生成邀请码

邀请码完整值只在生成当次展示，建议立即保存。

### 4.4 管理设备

设备页支持：

- 查看邀请码绑定状态
- 禁用并删除邀请码
- 恢复设备状态
- 解绑设备
- 为单台设备切换模板

切换模板不会对正在运行中的客户端做实时推送；客户端下次启动并强制拉取更新时，会自动使用新模板。

## 5. Android 客户端接入方式

### 5.1 服务端地址从哪里来

客户端通过 `BuildConfig.MANAGEMENT_API_URL` 调用 Worker 接口。

这个值的来源在 [buildSrc/src/main/kotlin/Helpers.kt](D:/Codex/nekobox-for-android/buildSrc/src/main/kotlin/Helpers.kt)：

- 优先读取 `local.properties` 里的 `MANAGEMENT_API_URL`
- 其次读取环境变量 `MANAGEMENT_API_URL`
- 如果未设置，则从 `REMOTE_CONFIG_URL` 推导出基础地址

也就是说，最稳妥的配置方式是在 `local.properties` 里加入：

```properties
MANAGEMENT_API_URL=https://你的-worker域名
REMOTE_CONFIG_URL=https://你的-worker域名/app-config.json
```

如果你还要保留内置订阅，也可以同时设置：

```properties
BUILTIN_SUB_URL=https://你的默认订阅地址
```

### 5.2 客户端与服务端交互链路

客户端激活和更新流程如下：

1. 输入邀请码
2. 客户端生成设备密钥对
3. 调用 `/api/v1/activate`
4. 保存返回的 `device_id`
5. 后续通过签名 URL 调用 `/api/v1/config`
6. 再根据返回的 `subscription_url` 拉取 `/api/v1/subscription`

对应代码入口：

- [app/src/main/java/io/nekohasekai/sagernet/managed/ManagedApiClient.kt](D:/Codex/nekobox-for-android/app/src/main/java/io/nekohasekai/sagernet/managed/ManagedApiClient.kt)
- [app/src/main/java/io/nekohasekai/sagernet/managed/ManagedSubscriptionCoordinator.kt](D:/Codex/nekobox-for-android/app/src/main/java/io/nekohasekai/sagernet/managed/ManagedSubscriptionCoordinator.kt)
- [app/src/main/java/io/nekohasekai/sagernet/managed/ManagedDeviceIdentity.kt](D:/Codex/nekobox-for-android/app/src/main/java/io/nekohasekai/sagernet/managed/ManagedDeviceIdentity.kt)

### 5.3 构建客户端

在项目根目录的 PowerShell 执行：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:assembleDebug
```

如果只想构建特定风味：

```powershell
.\gradlew.bat :app:assembleOssDebug
.\gradlew.bat :app:assembleFdroidDebug
.\gradlew.bat :app:assemblePlayDebug
.\gradlew.bat :app:assemblePreviewDebug
```

产物一般在：

- `app/build/outputs/apk/`

## 6. 自定义服务端与扩展点

你可以围绕 `cloudflare/sub-json/src/index.js` 做继续扩展，常见方向包括：

- 修改后台 UI
- 增加模板字段
- 接入自定义审计逻辑
- 调整设备激活和恢复策略
- 扩展 `/app-config.json` 返回内容

当前默认设计是：

- `invites` 表既承担邀请码记录，也承担设备绑定记录
- 模板切换只更新 `invites.template_id`
- 不新增实时推送链路
- 客户端下次启动时强制重新拉取配置

## 7. 常见问题

### 7.1 后台提示 `TOTP未配置`

原因通常是没有设置 `TOTP_SECRET_BASE32`。

处理：

- 重新执行 `wrangler secret put TOTP_SECRET_BASE32`
- 确认部署后访问的是最新 Worker 版本

### 7.2 设备页加载失败 / 500

优先检查：

- D1 是否已完成迁移
- `wrangler.jsonc` 的 D1 绑定是否正确
- Worker 是否是最新部署版本

### 7.3 模板切换后没有立刻变化

这是当前设计预期：

- 模板切换只影响后续拉取
- 客户端下次启动或下次强制更新时才会读取新模板

### 7.4 `MANAGEMENT_API_URL 未配置`

说明客户端构建时没有注入服务端地址。

处理：

- 在 `local.properties` 中设置 `MANAGEMENT_API_URL`
- 或通过环境变量传入

### 7.5 D1 / KV 绑定错误

优先检查：

- `wrangler.jsonc` 中的绑定 ID 是否替换成你的真实值
- 绑定名称是否仍与代码一致：`DB`、`CONFIG_KV`

## 8. 安全提醒

以下内容不要上传到公开仓库：

- `TOTP_SECRET_BASE32`
- `ADMIN_SESSION_SECRET`
- `local.properties` 中的私有地址或签名参数
- `cloudflare/sub-json/.wrangler/`
- 任何 keystore、证书、私钥、签名备份文件

本仓库已经按公开发布场景做了处理：

- `cloudflare` 目录允许上传
- Worker 配置改成模板占位
- 本地 Wrangler 缓存和签名文件应继续忽略

如果你曾经把敏感文件推送到公开远端，还需要额外做一次 Git 历史清理；本仓库当前只处理“后续不再上传”。

## 9. 上游与许可证说明

本项目基于 NekoBox for Android 定制，保留 GPL-3.0 许可证要求，并建议在对外发布时明确说明上游来源。

上游参考：

- [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)

如你继续公开分发修改版客户端，请自行确认许可证履行、源码公开和署名说明是否完整。
