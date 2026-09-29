```
命令行执行如下：
ech-win -l 127.0.0.1:30000 -f cf绑定域名[pages.dev]:443 -pyip tw.william.us.ci -token xxx -ip 优选域名或者ip(ipv4或ipv6)
ech-win -f cf绑定域名:443 -pyip tw.william.us.ci -token xxx -ip 104.16.0.0
ech-win -f cf绑定域名:443 -pyip 211.48.77.114:12312 -token xxx -ip 104.16.0.0

Usage of ech-win:
  -dns string
        ECH 查询 DoH 服务器 (default "dns.alidns.com/dns-query")
  -ech string
        ECH 查询域名 (default "cloudflare-ech.com")
  -f string
        服务端地址 (格式: x.x.workers.dev:443)
  -ip string
        指定服务端 IP（绕过 DNS 解析）
  -l string
        代理监听地址 (支持 SOCKS5 和 HTTP) (default "127.0.0.1:30000")
  -pyip string
        代理服务器 IP（用于 Worker 连接回退，proxyip）
  -token string
        身份验证令牌
```
##### 注：workers、pages、snippets三种部署都支持, TOKEN=xxx 部署时请更换

## 反代 IP（ProxyIP）

`-pyip` 即反代 IP：客户端在 `CONNECT` 消息中把该地址传给 Worker，
Worker 直连目标失败时改用该 IP 回退连接，链路为
`客户端 → Worker → 反代 IP → 目标`。

- 支持 IPv4、IPv6（需用 `[]` 包裹，如 `[2a00:1098:2b::1:6815:5881]`）和域名；
- 多个地址可用英文逗号分隔（`_worker.js` 中按列表依次尝试）；
- 留空则使用服务端 `_worker.js` 中内置的 `PROXY_IP`；
- 各平台 GUI 的对应设置项：**反代 IP（ProxyIP，可选）**，每个节点（Profile）独立保存。

支持情况：命令行客户端（`-pyip`）、`_worker.js`、Android 客户端（已支持，见
[ech-workers-android-gui-src/README.md](ech-workers-android-gui-src/README.md)）。
