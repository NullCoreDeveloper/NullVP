package driver

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math/rand"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

const (
	mailruUA                   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:155.0) Gecko/20100101 Firefox/155.0"
	mailruSaveChangesTemplate = `42["message",{"type":"saveChanges","changes":"[\"76;AgAAADEA//8BAOwbfF7pEAAALQEAAAMAAAAAAAAAAAAAAAAAAAAAAAAA9v///xoAAAAyADAAMgA2AC4AMgAuADEALgAyADIANgA4AA==\",\"35;BgAAADYAMgA3AAEAHAABAAAAAAAAAAEAAABhAAAAAAMAAAA=\",\"35;BgAAADYAMgA3AAEAHAABAAAAAQAAAAEAAABzAAAAAAMAAAA=\",\"35;BgAAADYAMgA3AAEAHAABAAAAAgAAAAEAAABkAAAAAAMAAAA=\"]","startSaveChanges":true,"endSaveChanges":true,"isCoAuthoring":false,"isExcel":false,"deleteIndex":null,"excelAdditionalInfo":"{\"UserId\":\"%s\",\"UserShortId\":\"%s\",\"CursorInfo\":\"14;BgAAADYAMgA3AAMAAAA=\"}","unlock":false,"releaseLocks":false}]`
)

var (
	mailruCursorRe = regexp.MustCompile(`"cursor":"[^;]+;([^"]+)"`)
)

type MailruDriver struct {
	mu            sync.Mutex
	running       atomic.Bool
	connected     atomic.Bool
	lastErr       string
	activeStreams atomic.Int32

	cancel      context.CancelFunc
	ctx         context.Context
	socksLn     net.Listener
	writeQueue  chan []byte
	wsConn      *websocket.Conn
	activeConns map[uint32]net.Conn
	streamSeed  atomic.Uint32
	rxBuf       []byte

	baseUserID string
	userCount  atomic.Int32
}

func NewMailruDriver() *MailruDriver {
	return &MailruDriver{
		activeConns: make(map[uint32]net.Conn),
	}
}

func (d *MailruDriver) Name() string {
	return "mailru"
}

func (d *MailruDriver) IsRunning() bool {
	return d.running.Load()
}

func (d *MailruDriver) ActiveStreams() int {
	return int(d.activeStreams.Load())
}

func (d *MailruDriver) LastError() string {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.lastErr
}

func (d *MailruDriver) setErr(err string) {
	d.mu.Lock()
	d.lastErr = err
	d.mu.Unlock()
}

func (d *MailruDriver) Start(ctx context.Context, configJson string, tunFd int, logPath string) error {
	if d.running.Swap(true) {
		_ = d.Stop()
	}

	configJson = decodeMailruUriIfPresent(configJson)

	var m map[string]interface{}
	if err := json.Unmarshal([]byte(configJson), &m); err != nil {
		return fmt.Errorf("mailru invalid json: %w", err)
	}

	docUrl := getStringField(m, "url", "mailruUrl", "weblink")
	if docUrl == "" {
		return fmt.Errorf("mailru document URL is required")
	}

	cookies := getStringField(m, "cookies")
	localPort := getIntField(m, "localPort", "port")
	if localPort <= 0 {
		localPort = 10808
	}

	d.ctx, d.cancel = context.WithCancel(ctx)
	d.baseUserID = fmt.Sprintf("%010d", rand.New(rand.NewSource(time.Now().UnixNano())).Intn(1000000000))
	d.writeQueue = make(chan []byte, 1024)

	// Bind SOCKS5 listener
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", localPort))
	if err != nil {
		d.running.Store(false)
		return fmt.Errorf("bind socks5 on :%d: %w", localPort, err)
	}
	d.socksLn = ln
	log.Printf("[MAILRU-DRIVER] Local SOCKS5 listening on 127.0.0.1:%d", localPort)

	// Start Mail.ru transport loop
	go d.transportLoop(docUrl, cookies)

	// Start SOCKS5 accept loop
	go d.socksAcceptLoop(ln)

	return nil
}

func (d *MailruDriver) Stop() error {
	if !d.running.Swap(false) {
		return nil
	}

	if d.cancel != nil {
		d.cancel()
	}

	if d.socksLn != nil {
		_ = d.socksLn.Close()
	}

	d.mu.Lock()
	if d.wsConn != nil {
		_ = d.wsConn.Close()
		d.wsConn = nil
	}
	for id, c := range d.activeConns {
		_ = c.Close()
		delete(d.activeConns, id)
	}
	d.mu.Unlock()

	d.connected.Store(false)
	d.activeStreams.Store(0)
	log.Println("[MAILRU-DRIVER] Stopped")
	return nil
}

func (d *MailruDriver) transportLoop(docUrl, cookies string) {
	weblink := normalizeWeblinkUrl(docUrl)

	jar, _ := cookiejar.New(nil)
	if cookies != "" {
		u, _ := url.Parse("https://cloud.mail.ru/")
		var list []*http.Cookie
		for _, part := range strings.Split(cookies, ";") {
			kv := strings.SplitN(strings.TrimSpace(part), "=", 2)
			if len(kv) == 2 {
				list = append(list, &http.Cookie{Name: strings.TrimSpace(kv[0]), Value: strings.TrimSpace(kv[1]), Path: "/"})
			}
		}
		jar.SetCookies(u, list)
	}

	for d.running.Load() {
		err := d.runWsSession(weblink, jar)
		if err != nil && d.running.Load() {
			d.setErr(err.Error())
			log.Printf("[MAILRU-DRIVER] Session error: %v, retrying in 3s...", err)
			select {
			case <-time.After(3 * time.Second):
			case <-d.ctx.Done():
				return
			}
		}
	}
}

func (d *MailruDriver) runWsSession(weblink string, jar *cookiejar.Jar) error {
	info, err := fetchMailruDocInfo(weblink, jar)
	if err != nil {
		return fmt.Errorf("fetchDocInfo: %w", err)
	}

	dialer := websocket.Dialer{
		EnableCompression: true,
		HandshakeTimeout:  15 * time.Second,
	}

	headers := http.Header{}
	headers.Set("User-Agent", mailruUA)
	headers.Set("Accept-Language", "en-US,en;q=0.9")
	headers.Set("Origin", "https://docs.datacloudmail.ru")

	conn, _, err := dialer.Dial(info.WsURL, headers)
	if err != nil {
		return fmt.Errorf("ws dial: %w", err)
	}
	defer conn.Close()

	d.mu.Lock()
	d.wsConn = conn
	d.mu.Unlock()

	userID := fmt.Sprintf("%s%03d", d.baseUserID, d.userCount.Add(1)%1000)

	// Socket.IO handshake
	if err := performSocketIOHandshake(conn, info.Token); err != nil {
		return fmt.Errorf("socket.io handshake: %w", err)
	}

	// Auth frame
	authMsg := map[string]interface{}{
		"type":                "auth",
		"docid":               info.DocKey,
		"documentCallbackUrl": info.CallbackURL,
		"token":               "fghhfgsjdgfjs",
		"user": map[string]interface{}{
			"id":        info.EditorUserID,
			"username":  userID,
			"indexUser": -1,
		},
		"editorType":         0,
		"lastOtherSaveTime":  -1,
		"block":              []interface{}{},
		"documentFormatSave": 65,
		"view":               false,
		"isCloseCoAuthoring": false,
		"openCmd": map[string]interface{}{
			"c":               "open",
			"id":              info.DocKey,
			"userid":          info.EditorUserID,
			"format":          info.FileType,
			"url":             info.DocURL,
			"title":           info.DocTitle,
			"lcid":            25,
			"nobase64":        true,
			"convertToOrigin": ".pdf.xps.oxps.djvu",
		},
		"lang":                  "ru",
		"mode":                  "edit",
		"permissions":           info.Permissions,
		"IsAnonymousUser":       false,
		"timezoneOffset":        -180,
		"coEditingMode":         "fast",
		"jwtOpen":               info.Token,
		"time":                  1000,
		"supportAuthChangesAck": true,
	}
	b, _ := json.Marshal([]interface{}{"message", authMsg})
	_ = conn.WriteMessage(websocket.TextMessage, []byte(fmt.Sprintf("42%s", string(b))))

	d.connected.Store(true)
	log.Printf("[MAILRU-DRIVER] Tunnel active via Mail.ru Doc %s", info.DocKey)

	// Writer goroutine
	wsWriterDone := make(chan struct{})
	go func() {
		defer close(wsWriterDone)
		for d.running.Load() {
			select {
			case data, ok := <-d.writeQueue:
				if !ok {
					return
				}
				p := base64.StdEncoding.EncodeToString(data)
				msg := fmt.Sprintf(`42["message",{"type":"cursor","cursor":"18;%s"}]`, p)
				d.mu.Lock()
				c := d.wsConn
				d.mu.Unlock()
				if c != nil {
					_ = c.SetWriteDeadline(time.Now().Add(10 * time.Second))
					if err := c.WriteMessage(websocket.TextMessage, []byte(msg)); err != nil {
						log.Printf("[MAILRU-DRIVER] WS write error: %v", err)
						return
					}
				}
			case <-d.ctx.Done():
				return
			}
		}
	}()

	// Keepalive & simulated editor activity
	go func() {
		ticker := time.NewTicker(10 * time.Second)
		defer ticker.Stop()
		for d.running.Load() && d.connected.Load() {
			select {
			case <-ticker.C:
				ka := `42["message",{"type":"cursor","cursor":"18;---KA---"}]`
				d.mu.Lock()
				c := d.wsConn
				d.mu.Unlock()
				if c != nil {
					_ = c.WriteMessage(websocket.TextMessage, []byte(ka))
				}
			case <-d.ctx.Done():
				return
			}
		}
	}()

	defer func() {
		d.connected.Store(false)
		d.mu.Lock()
		d.wsConn = nil
		d.mu.Unlock()
		<-wsWriterDone
	}()

	for d.running.Load() {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return err
		}
		text := string(msg)
		if text == "2" {
			_ = conn.WriteMessage(websocket.TextMessage, []byte("3"))
			continue
		}
		if text == "3" {
			continue
		}
		if strings.Contains(text, "cursor") {
			for _, m := range mailruCursorRe.FindAllStringSubmatch(text, -1) {
				if len(m) > 1 && m[1] != "---KA---" {
					dec, err := base64.StdEncoding.DecodeString(m[1])
					if err == nil && len(dec) > 0 {
						d.feedInboundFrames(dec)
					}
				}
			}
		}
	}
	return nil
}

func (d *MailruDriver) sendFrame(f Frame) {
	raw := Encode(nil, f)
	select {
	case d.writeQueue <- raw:
	default:
	}
}

func (d *MailruDriver) feedInboundFrames(b []byte) {
	d.mu.Lock()
	d.rxBuf = append(d.rxBuf, b...)
	var frames []Frame
	for {
		f, rest, ok := TakeFrame(d.rxBuf)
		if !ok {
			break
		}
		d.rxBuf = rest
		frames = append(frames, f)
	}
	d.mu.Unlock()

	for _, f := range frames {
		d.handleFrame(f)
	}
}

func (d *MailruDriver) handleFrame(f Frame) {
	switch f.Type {
	case FrameOpenOK:
		// Remote exit opened target stream
	case FrameOpenErr:
		log.Printf("[MAILRU-DRIVER] Remote stream %d open error: %s", f.StreamID, string(f.Payload))
		d.closeStream(f.StreamID)
	case FrameData:
		d.mu.Lock()
		c := d.activeConns[f.StreamID]
		d.mu.Unlock()
		if c != nil {
			_, _ = c.Write(f.Payload)
			d.sendFrame(Frame{Type: FrameAck, StreamID: f.StreamID, Payload: make([]byte, 4)})
		}
	case FrameClose:
		d.closeStream(f.StreamID)
	case FrameAck:
	}
}

func (d *MailruDriver) closeStream(streamID uint32) {
	d.mu.Lock()
	c := d.activeConns[streamID]
	delete(d.activeConns, streamID)
	d.mu.Unlock()
	if c != nil {
		_ = c.Close()
		d.activeStreams.Add(-1)
	}
}

func (d *MailruDriver) socksAcceptLoop(ln net.Listener) {
	for d.running.Load() {
		conn, err := ln.Accept()
		if err != nil {
			if !d.running.Load() {
				return
			}
			continue
		}
		go d.handleSocksConn(conn)
	}
}

func (d *MailruDriver) handleSocksConn(conn net.Conn) {
	defer conn.Close()

	// SOCKS5 greeting
	buf := make([]byte, 256)
	n, err := io.ReadFull(conn, buf[:2])
	if err != nil || n < 2 || buf[0] != 0x05 {
		return
	}
	numMethods := int(buf[1])
	methods := make([]byte, numMethods)
	if _, err := io.ReadFull(conn, methods); err != nil {
		return
	}
	// Handshake response: NO AUTH (0x00)
	_, _ = conn.Write([]byte{0x05, 0x00})

	// SOCKS5 request
	header := make([]byte, 4)
	if _, err := io.ReadFull(conn, header); err != nil || header[0] != 0x05 || header[1] != 0x01 {
		return // only CONNECT (0x01) supported
	}

	var host string
	switch header[3] {
	case 0x01: // IPv4
		ip := make([]byte, 4)
		if _, err := io.ReadFull(conn, ip); err != nil {
			return
		}
		host = net.IP(ip).String()
	case 0x03: // Domain
		lBuf := make([]byte, 1)
		if _, err := io.ReadFull(conn, lBuf); err != nil {
			return
		}
		domain := make([]byte, int(lBuf[0]))
		if _, err := io.ReadFull(conn, domain); err != nil {
			return
		}
		host = string(domain)
	case 0x04: // IPv6
		ip := make([]byte, 16)
		if _, err := io.ReadFull(conn, ip); err != nil {
			return
		}
		host = net.IP(ip).String()
	default:
		return
	}

	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(conn, portBuf); err != nil {
		return
	}
	port := binary.BigEndian.Uint16(portBuf)
	target := fmt.Sprintf("%s:%d", host, port)

	streamID := d.streamSeed.Add(1)
	d.mu.Lock()
	d.activeConns[streamID] = conn
	d.mu.Unlock()
	d.activeStreams.Add(1)

	// Send Open frame through Mail.ru tunnel
	d.sendFrame(Frame{Type: FrameOpen, StreamID: streamID, Payload: []byte(target)})

	// Send SOCKS5 success reply
	_, _ = conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})

	// Pump client data to Mail.ru tunnel
	pumpBuf := make([]byte, 16*1024)
	for {
		rn, rerr := conn.Read(pumpBuf)
		if rn > 0 {
			d.sendFrame(Frame{Type: FrameData, StreamID: streamID, Payload: append([]byte(nil), pumpBuf[:rn]...)})
		}
		if rerr != nil {
			break
		}
	}

	d.sendFrame(Frame{Type: FrameClose, StreamID: streamID})
	d.closeStream(streamID)
}

func performSocketIOHandshake(conn *websocket.Conn, token string) error {
	_ = conn.SetReadDeadline(time.Now().Add(15 * time.Second))
	defer conn.SetReadDeadline(time.Time{})

	for {
		mt, p, err := conn.ReadMessage()
		if err != nil {
			return err
		}
		if mt == websocket.TextMessage && strings.HasPrefix(string(p), "0{") {
			break
		}
	}

	_ = conn.WriteMessage(websocket.TextMessage, []byte(fmt.Sprintf(`40{"token":"%s"}`, token)))

	for {
		mt, p, err := conn.ReadMessage()
		if err != nil {
			return err
		}
		if mt == websocket.TextMessage && strings.HasPrefix(string(p), "40") {
			break
		}
	}
	return nil
}

type mailruDocDetails struct {
	Token        string
	DocKey       string
	WsURL        string
	FileType     string
	DocURL       string
	DocTitle     string
	Permissions  map[string]interface{}
	CallbackURL  string
	EditorUserID string
}

func fetchMailruDocInfo(weblink string, jar *cookiejar.Jar) (mailruDocDetails, error) {
	client := &http.Client{Jar: jar, Timeout: 15 * time.Second}
	reqBody, _ := json.Marshal(map[string]string{
		"x-email":  "anonym",
		"public":   "/" + weblink,
		"platform": "desktop_web",
	})

	apiURL := "https://cloud.mail.ru/api/v4/r7/edit"
	req, _ := http.NewRequest("POST", apiURL, bytes.NewBuffer(reqBody))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/plain, */*")
	req.Header.Set("Accept-Language", "en-US,en;q=0.9")
	req.Header.Set("User-Agent", mailruUA)
	req.Header.Set("X-Api-Version", "4")
	req.Header.Set("Referer", fmt.Sprintf("https://cloud.mail.ru/public/%s?weblink=%s", weblink, weblink))

	resp, err := client.Do(req)
	if err != nil {
		return mailruDocDetails{}, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return mailruDocDetails{}, fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(b))
	}

	bodyBytes, _ := io.ReadAll(resp.Body)
	var res map[string]interface{}
	if err := json.Unmarshal(bodyBytes, &res); err != nil {
		return mailruDocDetails{}, err
	}

	apiBase, _ := res["api"].(string)
	token, _ := res["token"].(string)
	doc, _ := res["document"].(map[string]interface{})
	if doc == nil {
		return mailruDocDetails{}, errors.New("document missing")
	}

	docKey, _ := doc["key"].(string)
	fileType, _ := doc["fileType"].(string)
	docURL, _ := doc["url"].(string)
	docTitle, _ := doc["title"].(string)
	perms, _ := doc["permissions"].(map[string]interface{})

	ec, _ := res["editorConfig"].(map[string]interface{})
	callbackURL := ""
	editorUserID := ""
	if ec != nil {
		callbackURL, _ = ec["callbackUrl"].(string)
		if u, _ := ec["user"].(map[string]interface{}); u != nil {
			editorUserID, _ = u["id"].(string)
		}
	}

	wsBase := strings.Replace(apiBase, "https://", "wss://", 1)
	wsURL := fmt.Sprintf("%s/doc/%s/c/?EIO=4&transport=websocket", wsBase, docKey)

	return mailruDocDetails{
		Token:        token,
		DocKey:       docKey,
		WsURL:        wsURL,
		FileType:     fileType,
		DocURL:       docURL,
		DocTitle:     docTitle,
		Permissions:  perms,
		CallbackURL:  callbackURL,
		EditorUserID: editorUserID,
	}, nil
}

func normalizeWeblinkUrl(raw string) string {
	raw = strings.TrimSpace(raw)
	for _, p := range []string{
		"https://cloud.mail.ru/public/",
		"http://cloud.mail.ru/public/",
		"https://cloud.mail.ru/",
		"http://cloud.mail.ru/",
	} {
		if strings.HasPrefix(raw, p) {
			return strings.Trim(strings.TrimPrefix(raw, p), "/")
		}
	}
	return raw
}

func decodeMailruUriIfPresent(uri string) string {
	trimmed := strings.TrimSpace(uri)
	payload := ""
	if strings.HasPrefix(strings.ToLower(trimmed), "mailru://") {
		payload = trimmed[9:]
	} else if strings.HasPrefix(strings.ToLower(trimmed), "openflux://") {
		payload = trimmed[11:]
	} else {
		return uri
	}

	if idx := strings.Index(payload, "#"); idx != -1 {
		payload = payload[:idx]
	}
	s := strings.ReplaceAll(strings.ReplaceAll(payload, "-", "+"), "_", "/")
	if mod := len(s) % 4; mod != 0 {
		s += strings.Repeat("=", 4-mod)
	}
	decoded, err := base64.StdEncoding.DecodeString(s)
	if err == nil && len(decoded) > 0 {
		return string(decoded)
	}
	return uri
}

func getStringField(m map[string]interface{}, keys ...string) string {
	for _, k := range keys {
		if v, ok := m[k]; ok {
			if s, ok := v.(string); ok && s != "" {
				return s
			}
		}
	}
	return ""
}

func getIntField(m map[string]interface{}, keys ...string) int {
	for _, k := range keys {
		if v, ok := m[k]; ok {
			switch val := v.(type) {
			case float64:
				return int(val)
			case int:
				return val
			case string:
				if n, err := strconv.Atoi(val); err == nil {
					return n
				}
			}
		}
	}
	return 0
}
