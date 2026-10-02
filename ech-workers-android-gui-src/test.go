package tunnel

// 节点测速（Android「测试」按钮的后端）
//
// 设计要点：
//  1. 只使用局部配置，绝不读写 workers.go 里的全局参数（serverAddr/token/serverIP/echList/proxyIP），
//     因此可以在 VPN 运行期间安全调用，不会干扰正在使用的节点。
//  2. 不建立任何 tun/VpnService，只开普通 TCP(WebSocket) 连接，
//     不占用 Android 系统那唯一的 VPN 隧道名额。
//  3. 每个「节点 × 站点」各建一条独立隧道，与真实使用一致
//     （真实路径同样是每条客户端连接各拨一次 WSS，见 handleTunnel）。
//  4. 计时口径：ws_ms = WSS/TLS 握手（不含 ECH 查询，真实路径里 ECH 只在启动时查一次）；
//     ms = 发出 CONNECT 到收到目标站 HTTP 响应头（TTFB）；total_ms = 两者之和。

import (
	"bufio"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	testECHCacheTTL        = 5 * time.Minute
	testWSHandshakeTimeout = 10 * time.Second
	testPingInterval       = 10 * time.Second
	testUserAgent          = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

// testNodeConfig 一次测速所需的节点配置（与全局参数隔离）
type testNodeConfig struct {
	wsServer  string
	dns       string
	echDomain string
	serverIP  string
	token     string
	proxyIP   string
}

// siteTestResult 单个「节点 → 站点」的测速结果，序列化为 JSON 交给 Java 侧
type siteTestResult struct {
	OK      bool   `json:"ok"`
	MS      int64  `json:"ms"`
	WSMS    int64  `json:"ws_ms"`
	TotalMS int64  `json:"total_ms"`
	ECHMS   int64  `json:"ech_ms"`
	Status  int    `json:"status"`
	URL     string `json:"url"`
	Err     string `json:"err"`
}

func (r siteTestResult) toJSON() string {
	b, err := json.Marshal(r)
	if err != nil {
		return fmt.Sprintf(`{"ok":false,"ms":-1,"ws_ms":-1,"total_ms":-1,"ech_ms":%d,"status":0,"url":%q,"err":"结果序列化失败"}`,
			r.ECHMS, r.URL)
	}
	return string(b)
}

// ======================== ECH 配置缓存 ========================

type testECHCacheEntry struct {
	data []byte
	at   time.Time
}

var (
	testECHCacheMu sync.Mutex
	testECHCache   = make(map[string]testECHCacheEntry)
)

// echForTest 获取指定节点的 ECH 配置，按 dns|echDomain 缓存。
// 与真实路径一致：ECH 只在节点启用时查询一次，后续连接复用。
func echForTest(cfg testNodeConfig) (data []byte, echMS int64, err error) {
	dns := cfg.dns
	if dns == "" {
		dns = "dns.alidns.com/dns-query"
	}
	domain := cfg.echDomain
	if domain == "" {
		domain = "cloudflare-ech.com"
	}
	key := dns + "|" + domain

	testECHCacheMu.Lock()
	if entry, ok := testECHCache[key]; ok && time.Since(entry.at) < testECHCacheTTL {
		cached := entry.data
		testECHCacheMu.Unlock()
		return cached, 0, nil
	}
	testECHCacheMu.Unlock()

	start := time.Now()
	b64, qerr := queryHTTPSRecord(domain, dns)
	echMS = time.Since(start).Milliseconds()
	if qerr != nil {
		return nil, echMS, fmt.Errorf("ECH 查询失败: %v", qerr)
	}
	if b64 == "" {
		return nil, echMS, errors.New("ECH 查询失败: 未找到 ECH 参数")
	}
	raw, derr := base64.StdEncoding.DecodeString(b64)
	if derr != nil {
		return nil, echMS, fmt.Errorf("ECH 查询失败: Base64 解码失败: %v", derr)
	}

	testECHCacheMu.Lock()
	testECHCache[key] = testECHCacheEntry{data: raw, at: time.Now()}
	testECHCacheMu.Unlock()
	log.Printf("[测速] ECH 配置已缓存: %s (%d 字节, 耗时 %d ms)", key, len(raw), echMS)
	return raw, echMS, nil
}

// ======================== 与节点建立 WebSocket ========================

// testDialFunc 建立到节点的 WebSocket 连接；可注入以便本地桩测试。
type testDialFunc func(cfg testNodeConfig, echBytes []byte) (*websocket.Conn, error)

// dialWSTest 是 dialWebSocketWithECH 的参数化版本：配置全部来自 cfg，不依赖全局参数。
func dialWSTest(cfg testNodeConfig, echBytes []byte) (*websocket.Conn, error) {
	host, port, path, err := parseServerAddr(cfg.wsServer)
	if err != nil {
		return nil, err
	}

	tlsCfg, err := buildTLSConfigWithECH(host, echBytes)
	if err != nil {
		return nil, fmt.Errorf("构建 TLS 配置失败: %v", err)
	}

	dialer := websocket.Dialer{
		TLSClientConfig:  tlsCfg,
		HandshakeTimeout: testWSHandshakeTimeout,
		ReadBufferSize:   65536,
		WriteBufferSize:  65536,
	}
	if cfg.token != "" {
		dialer.Subprotocols = []string{cfg.token}
	}
	if cfg.serverIP != "" {
		ip := cfg.serverIP
		dialer.NetDial = func(network, address string) (net.Conn, error) {
			_, p, serr := net.SplitHostPort(address)
			if serr != nil {
				return nil, serr
			}
			return net.DialTimeout(network, net.JoinHostPort(ip, p), testWSHandshakeTimeout)
		}
	}

	wsURL := fmt.Sprintf("wss://%s:%s%s", host, port, path)
	conn, _, err := dialer.Dial(wsURL, nil)
	if err != nil {
		return nil, err
	}
	return conn, nil
}

// ======================== WebSocket → net.Conn ========================

// wsTunnelConn 把隧道 WebSocket 包装成 net.Conn，供 tls.Client / HTTP 读写使用。
// 数据帧语义与 handleTunnel 保持一致：二进制帧为数据，文本帧 "CLOSE" 表示对端关闭。
type wsTunnelConn struct {
	ws      *websocket.Conn
	writeMu sync.Mutex
	pending []byte
	closed  bool
}

func newWSTunnelConn(ws *websocket.Conn) *wsTunnelConn {
	return &wsTunnelConn{ws: ws}
}

func (c *wsTunnelConn) Read(p []byte) (int, error) {
	for {
		if len(c.pending) > 0 {
			n := copy(p, c.pending)
			c.pending = c.pending[n:]
			return n, nil
		}

		mt, data, err := c.ws.ReadMessage()
		if err != nil {
			return 0, err
		}
		if mt == websocket.TextMessage {
			if string(data) == "CLOSE" {
				return 0, io.EOF
			}
			continue // 忽略其它控制文本帧
		}
		if len(data) == 0 {
			continue
		}

		n := copy(p, data)
		if n < len(data) {
			c.pending = append(c.pending[:0], data[n:]...)
		}
		return n, nil
	}
}

func (c *wsTunnelConn) Write(p []byte) (int, error) {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	if err := c.ws.WriteMessage(websocket.BinaryMessage, p); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *wsTunnelConn) Close() error {
	if c.closed {
		return nil
	}
	c.closed = true
	c.writeMu.Lock()
	_ = c.ws.WriteMessage(websocket.TextMessage, []byte("CLOSE"))
	c.writeMu.Unlock()
	return c.ws.Close()
}

func (c *wsTunnelConn) LocalAddr() net.Addr  { return c.ws.LocalAddr() }
func (c *wsTunnelConn) RemoteAddr() net.Addr { return c.ws.RemoteAddr() }

func (c *wsTunnelConn) SetDeadline(t time.Time) error {
	if err := c.ws.SetReadDeadline(t); err != nil {
		return err
	}
	return c.ws.SetWriteDeadline(t)
}

func (c *wsTunnelConn) SetReadDeadline(t time.Time) error  { return c.ws.SetReadDeadline(t) }
func (c *wsTunnelConn) SetWriteDeadline(t time.Time) error { return c.ws.SetWriteDeadline(t) }

// ======================== 对外接口 ========================

// TestSite 通过指定节点（Worker + ECH）访问 targetURL，返回 JSON 结果字符串：
//
//	{"ok":true,"ms":778,"ws_ms":456,"total_ms":1234,"ech_ms":120,"status":200,"url":"...","err":""}
//
// wsServer 形如 "域名:443" 或 "域名:443/path"；timeoutMs <= 0 时默认 15000。
// 说明：本函数不建立 VPN 隧道，可在代理运行期间安全调用。
func TestSite(wsServer, dns, ech, ip, tkn, pyip, targetURL string, timeoutMs int) (result string) {
	// gomobile 调用越界会带崩整个 App，这里兜底
	defer func() {
		if r := recover(); r != nil {
			result = siteTestResult{
				MS: -1, WSMS: -1, TotalMS: -1, URL: targetURL,
				Err: fmt.Sprintf("内部错误: %v", r),
			}.toJSON()
		}
	}()

	cfg := testNodeConfig{
		wsServer:  strings.TrimSpace(wsServer),
		dns:       strings.TrimSpace(dns),
		echDomain: strings.TrimSpace(ech),
		serverIP:  strings.TrimSpace(ip),
		token:     strings.TrimSpace(tkn),
		proxyIP:   strings.TrimSpace(pyip),
	}

	res := siteTestResult{MS: -1, WSMS: -1, TotalMS: -1, URL: targetURL}
	if cfg.wsServer == "" {
		res.Err = "未配置服务器地址"
		return res.toJSON()
	}
	if _, _, _, err := parseServerAddr(cfg.wsServer); err != nil {
		res.Err = fmt.Sprintf("无效的服务器地址: %v", err)
		return res.toJSON()
	}

	echBytes, echMS, err := echForTest(cfg)
	res.ECHMS = echMS
	if err != nil {
		res.Err = err.Error()
		return res.toJSON()
	}

	return testSiteWith(cfg, targetURL, timeoutMs, echBytes, echMS, dialWSTest)
}

// testSiteWith 执行一次「节点 → 站点」测量。
// echBytes/echMS 由调用方提供（便于桩测试注入假配置），dialFn 同理。
func testSiteWith(cfg testNodeConfig, targetURL string, timeoutMs int, echBytes []byte, echMS int64, dialFn testDialFunc) string {
	res := siteTestResult{MS: -1, WSMS: -1, TotalMS: -1, ECHMS: echMS, URL: targetURL}

	if timeoutMs <= 0 {
		timeoutMs = 15000
	}
	deadline := time.Now().Add(time.Duration(timeoutMs) * time.Millisecond)

	u, err := url.Parse(targetURL)
	if err != nil || u.Host == "" {
		res.Err = "无效的目标地址"
		return res.toJSON()
	}
	host := u.Hostname()
	port := u.Port()
	if port == "" {
		if u.Scheme == "https" {
			port = "443"
		} else {
			port = "80"
		}
	}
	target := net.JoinHostPort(host, port)

	// 1) 建立到节点的 WSS（不含 ECH 查询耗时）
	t0 := time.Now()
	wsConn, err := dialFn(cfg, echBytes)
	if err != nil {
		res.Err = testErrText("节点连接失败", err)
		return res.toJSON()
	}
	res.WSMS = time.Since(t0).Milliseconds()
	defer wsConn.Close()

	// 2) 隧道握手：与 handleTunnel 完全相同的报文格式
	connectMsg := fmt.Sprintf("CONNECT:%s|", target)
	if cfg.proxyIP != "" {
		connectMsg = fmt.Sprintf("CONNECT:%s||%s", target, cfg.proxyIP)
	}
	_ = wsConn.SetWriteDeadline(deadline)
	if err := wsConn.WriteMessage(websocket.TextMessage, []byte(connectMsg)); err != nil {
		res.Err = testErrText("隧道建立失败", err)
		return res.toJSON()
	}
	_ = wsConn.SetReadDeadline(deadline)
	_, msg, err := wsConn.ReadMessage()
	if err != nil {
		if isTimeout(err) {
			res.Err = "隧道建立超时"
		} else {
			res.Err = testErrText("隧道建立失败", err)
		}
		return res.toJSON()
	}
	response := string(msg)
	if strings.HasPrefix(response, "ERROR:") {
		res.Err = "隧道建立失败: " + response
		return res.toJSON()
	}
	if response != "CONNECTED" {
		res.Err = fmt.Sprintf("隧道建立失败: 意外响应 %s", response)
		return res.toJSON()
	}

	// 3) 保活：与真实路径一致（每 10s 一个 ping）
	stopPing := make(chan struct{})
	defer close(stopPing)
	go func() {
		ticker := time.NewTicker(testPingInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				_ = wsConn.WriteControl(websocket.PingMessage, nil, time.Now().Add(5*time.Second))
			case <-stopPing:
				return
			}
		}
	}()

	// 4) TLS + HTTP GET，计时到收到响应头（TTFB）
	t1 := time.Now()
	finish := func(e error) string {
		res.MS = time.Since(t1).Milliseconds()
		if e != nil {
			res.Err = e.Error()
		} else {
			res.OK = true
			res.TotalMS = res.WSMS + res.MS
		}
		return res.toJSON()
	}

	tunnel := newWSTunnelConn(wsConn)
	_ = tunnel.SetDeadline(deadline)

	var rw net.Conn = tunnel
	if u.Scheme == "https" {
		roots, rerr := x509.SystemCertPool()
		if rerr != nil {
			return finish(fmt.Errorf("加载系统根证书失败: %v", rerr))
		}
		tlsConn := tls.Client(tunnel, &tls.Config{
			MinVersion: tls.VersionTLS12,
			ServerName: host,
			RootCAs:    roots,
		})
		if herr := tlsConn.HandshakeContext(context.Background()); herr != nil {
			return finish(errors.New(testErrText("TLS 握手失败", herr)))
		}
		rw = tlsConn
	}

	req, rerr := http.NewRequest("GET", targetURL, nil)
	if rerr != nil {
		return finish(fmt.Errorf("无效的目标地址: %v", rerr))
	}
	path := u.RequestURI()
	if path == "" {
		path = "/"
	}
	request := fmt.Sprintf("GET %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: %s\r\nAccept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n",
		path, u.Host, testUserAgent)
	if _, werr := io.WriteString(rw, request); werr != nil {
		if isTimeout(werr) {
			return finish(errors.New("请求超时"))
		}
		return finish(errors.New(testErrText("请求失败", werr)))
	}

	resp, rerr := http.ReadResponse(bufio.NewReader(rw), req)
	if rerr != nil {
		if isTimeout(rerr) {
			return finish(errors.New("请求超时"))
		}
		return finish(errors.New(testErrText("请求失败", rerr)))
	}
	defer resp.Body.Close()

	res.Status = resp.StatusCode
	return finish(nil)
}

// ======================== 错误文本 ========================

func isTimeout(err error) bool {
	if err == nil {
		return false
	}
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return true
	}
	return strings.Contains(strings.ToLower(err.Error()), "timeout")
}

// testErrText 把底层错误转成给用户看的中文短句
func testErrText(prefix string, err error) string {
	if err == nil {
		return prefix
	}
	if isTimeout(err) {
		return prefix + ": 超时"
	}
	if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
		return prefix + ": 连接被对方关闭"
	}
	return prefix + ": " + err.Error()
}
