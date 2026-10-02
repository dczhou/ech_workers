# ECH-Workers Android 客户端

## 编译 aar（gomobile）

Java 侧通过 `com.ech.workers.tunnel.Tunnel` 调用 `workers.go`，
需要先用 gomobile 编译成 `ech-workers.aar` 放在本目录下（`build.gradle` 通过 `flatDir` 引用）。

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init

# 在仓库根目录执行
bash ech-workers-android-gui-src/build-aar.sh
```

也可以直接执行等价命令：

```bash
gomobile bind -target=android -androidapi 24 -javapkg=com.ech.workers \
  -o ech-workers-android-gui-src/ech-workers.aar ./ech-workers-android-gui-src
```

> 注意：`workers.go` 中导出函数（如 `StartSocksProxy`）的签名变更后，
> 必须重新生成 aar，否则 Java 侧会与旧 aar 不匹配。
> 脚本会在需要时自动向 Go 模块补 `golang.org/x/mobile` 依赖（仅影响本次检出）。

## 打包 APK

**在线打包（推荐）**：仓库自带 [`.github/workflows/build-android.yml`](../.github/workflows/build-android.yml)，
在 Actions 里手动 `Run workflow` 并填写发布 tag（如 `v1.1`），或推送 `v*` 标签即可自动：

1. gomobile 生成 `ech-workers.aar`
2. Gradle 打包 4 个 ABI + universal 共 5 个 APK
3. 上传 Artifact，并创建/更新对应的 GitHub Release

签名密钥保存在仓库 Secrets：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`；
未配置时自动降级为 debug 签名 APK。

**本地打包**：

```bash
# 1) 生成 aar（需要 gomobile + Android NDK）
bash ech-workers-android-gui-src/build-aar.sh

# 2) 打包（需要 Android SDK 34 + JDK 17）
cd ech-workers-android-gui-src
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease      # 无 store.properties 时产出未签名 APK
```

使用发布签名时，把密钥库放到本目录并在 `store.properties` 中填写：

```properties
storeFile=release.keystore
storePassword=***
keyAlias=***
keyPassword=***
```

## 功能设置

| 设置项 | 说明 |
| --- | --- |
| 服务器地址 | CF Workers/Pages 绑定域名，格式 `域名:端口` |
| 本地代理端口 | 本地 SOCKS5/HTTP 监听端口 |
| ECH DOH 服务器 | 查询 ECH 公钥使用的 DoH 服务器 |
| ECH 公钥查询域名 | 默认 `cloudflare-ech.com` |
| 优选 IP | 直连 Worker 使用的优选 IP/域名（绕过 DNS 解析） |
| **反代 IP（ProxyIP）** | 可选。Worker 直连目标失败时用于回退的 IP/域名，留空则使用 Worker 端内置的 ProxyIP |
| 身份令牌 | 与服务端 `TOKEN` 一致的鉴权令牌 |

### 反代 IP（ProxyIP）

对应命令行的 `-pyip` 参数，链路为
`客户端 → Worker（CONNECT:目标|首帧|反代IP） → 反代 IP → 目标`。

* 支持 IPv4、IPv6（需用 `[]` 包裹，如 `[2a00:1098:2b::1:6815:5881]`）、域名；
* 支持多个值用英文逗号分隔（如 `1.2.3.4,proxyip.example.com`）；
* 留空表示不指定，由服务端 `_worker.js` 中的内置 `PROXY_IP` 决定；
* 每个配置节点（Profile）独立保存该设置。

### 节点测速（测试按钮）

节点选择框下方按键行的 **测试** 按钮会依次测试**所有已保存节点**，每个节点分别访问：

| 目标站点 | 代表的场景 |
| --- | --- |
| `https://myip.ms/` | 目标站点**未使用** Cloudflare CDN |
| `https://whatismyipaddress.com/` | 目标站点**使用** Cloudflare CDN |

每站显示 `合计 = 建连 + 访问`：

* **建连**：与该节点建立 `WSS(TLS 1.3 + ECH)` 的耗时（不含 ECH 公钥查询 —— 真实使用中该查询只在启动时做一次，故两站可比）；
* **访问**：发出 `CONNECT` 到收到目标站 HTTP 响应头（TTFB）的耗时，含 Worker→站点连接与 TLS 握手；
* 颜色：合计 <1s 绿、1~3s 橙、>3s 或失败 红；附加 `HTTP xxx` 表示连上了但状态码非 200（例如被 CF 拦截）。

行为约定：

* 测试**串行**执行（任意时刻只有一条测试连接），可随时取消；
* 测试**不建立 VPN 隧道**，不占用 Android 唯一的 VPN 名额，因此**代理运行期间也能测**，也不需要先连上节点；
* 测完只展示结果，**不会自动切换节点**，结果也不写回配置。

> ⚠️ 结果口径：测速流量由 App 自身发出（App 已被排除在 tun 之外），因此**不包含系统 VPN 的本地开销**
> （tun 设备 + hev-socks5-tunnel 用户态 TCP/IP 栈 + 本地回环 SOCKS5），真实使用会比显示的数值略慢。
> 该开销对各节点大致相同，**节点之间的排序不受影响**。
> 另外，若当前有其它 VPN 应用在运行，测速流量会经过那条 VPN，结果不可比。
