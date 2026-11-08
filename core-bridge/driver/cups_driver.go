package driver

import (
	"bytes"
	"compress/flate"
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

var cupsBaseRoomURL = "https://interview.cups.online/live-coding/"

const (
	cupsUA         = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
	bytesPerNumber = 6
)

var (
	reMetaConnToken = regexp.MustCompile(`<meta[^>]+name="centrifuge-connection-token"[^>]+content="([^"]+)"`)
	reMetaConnURL   = regexp.MustCompile(`<meta[^>]+name="centrifuge-connection-url"[^>]+content="([^"]+)"`)
	reMetaSubURL    = regexp.MustCompile(`<meta[^>]+name="centrifuge-subscription-token-url"[^>]+content="([^"]+)"`)
	reDataUserUUID  = regexp.MustCompile(`data-user="\{&quot;uuid&quot;:\s*&quot;([0-9a-f-]{36})&quot;`)
)

type CupsDriver struct {
	mu            sync.Mutex
	running       atomic.Bool
	connected     atomic.Bool
	lastErr       string
	activeStreams atomic.Int32

	cancel      context.CancelFunc
	ctx         context.Context
	socksLn     net.Listener
	transport   *cupsonlineTransport
	activeConns map[uint32]net.Conn
	streamSeed  atomic.Uint32
	rxBuf       []byte
}

func NewCupsDriver() *CupsDriver {
	return &CupsDriver{
		activeConns: make(map[uint32]net.Conn),
	}
}

func (d *CupsDriver) Name() string {
	return "cupsonline"
}

func (d *CupsDriver) IsRunning() bool {
	return d.running.Load()
}

func (d *CupsDriver) ActiveStreams() int {
	return int(d.activeStreams.Load())
}

func (d *CupsDriver) LastError() string {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.lastErr
}

func (d *CupsDriver) Start(ctx context.Context, configJson string, tunFd int, logPath string) error {
	if d.running.Swap(true) {
		_ = d.Stop()
	}

	configJson = decodeCupsUriIfPresent(configJson)

	var m map[string]interface{}
	if err := json.Unmarshal([]byte(configJson), &m); err != nil {
		d.running.Store(false)
		return fmt.Errorf("cupsonline invalid json: %w", err)
	}

	rooms := getStringField(m, "rooms")
	localPort := getIntField(m, "localPort", "port")
	if localPort <= 0 {
		localPort = 10808
	}

	d.ctx, d.cancel = context.WithCancel(ctx)
	d.transport = newCupsonlineTransport(rooms, false)
	d.transport.Receive(d.handleRx)

	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", localPort))
	if err != nil {
		d.running.Store(false)
		return fmt.Errorf("bind socks5 on :%d: %w", localPort, err)
	}
	d.socksLn = ln

	if err := d.transport.Start(); err != nil {
		_ = ln.Close()
		d.running.Store(false)
		return fmt.Errorf("start cupsonline transport: %w", err)
	}

	go d.socksAcceptLoop(ln)
	return nil
}

func (d *CupsDriver) safeSend(data []byte) error {
	d.mu.Lock()
	tr := d.transport
	running := d.running.Load()
	d.mu.Unlock()
	if tr == nil || !running {
		return errors.New("cupsonline stopped")
	}
	return tr.Send(data)
}

func (d *CupsDriver) Stop() error {
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
	for id, c := range d.activeConns {
		_ = c.Close()
		delete(d.activeConns, id)
	}
	tr := d.transport
	d.transport = nil
	d.mu.Unlock()

	if tr != nil {
		_ = tr.Stop()
	}

	d.connected.Store(false)
	d.activeStreams.Store(0)
	return nil
}

func (d *CupsDriver) handleRx(data []byte) {
	d.mu.Lock()
	d.rxBuf = append(d.rxBuf, data...)
	d.mu.Unlock()

	for {
		d.mu.Lock()
		f, rem, ok := TakeFrame(d.rxBuf)
		if !ok {
			d.mu.Unlock()
			break
		}
		d.rxBuf = rem
		d.mu.Unlock()

		d.dispatch(f)
	}
}

func (d *CupsDriver) dispatch(f Frame) {
	switch f.Type {
	case FrameData:
		d.mu.Lock()
		c := d.activeConns[f.StreamID]
		d.mu.Unlock()
		if c != nil {
			_, _ = c.Write(f.Payload)
		}
	case FrameClose, FrameOpenErr:
		d.closeStream(f.StreamID)
	}
}

func (d *CupsDriver) closeStream(streamID uint32) {
	d.mu.Lock()
	c := d.activeConns[streamID]
	delete(d.activeConns, streamID)
	d.mu.Unlock()
	if c != nil {
		_ = c.Close()
		d.activeStreams.Add(-1)
	}
}

func (d *CupsDriver) socksAcceptLoop(ln net.Listener) {
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

func (d *CupsDriver) handleSocksConn(conn net.Conn) {
	defer conn.Close()

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
	_, _ = conn.Write([]byte{0x05, 0x00})

	header := make([]byte, 4)
	if _, err := io.ReadFull(conn, header); err != nil || header[0] != 0x05 || header[1] != 0x01 {
		return
	}

	var host string
	switch header[3] {
	case 0x01:
		ip := make([]byte, 4)
		if _, err := io.ReadFull(conn, ip); err != nil {
			return
		}
		host = net.IP(ip).String()
	case 0x03:
		lBuf := make([]byte, 1)
		if _, err := io.ReadFull(conn, lBuf); err != nil {
			return
		}
		domain := make([]byte, int(lBuf[0]))
		if _, err := io.ReadFull(conn, domain); err != nil {
			return
		}
		host = string(domain)
	case 0x04:
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

	openFrame := Encode(nil, Frame{Type: FrameOpen, StreamID: streamID, Payload: []byte(target)})
	if err := d.safeSend(openFrame); err != nil {
		d.closeStream(streamID)
		return
	}

	_, _ = conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})

	pumpBuf := make([]byte, 16*1024)
	for {
		rn, rerr := conn.Read(pumpBuf)
		if rn > 0 {
			dataFrame := Encode(nil, Frame{Type: FrameData, StreamID: streamID, Payload: pumpBuf[:rn]})
			if err := d.safeSend(dataFrame); err != nil {
				break
			}
		}
		if rerr != nil {
			break
		}
	}

	closeFrame := Encode(nil, Frame{Type: FrameClose, StreamID: streamID})
	_ = d.safeSend(closeFrame)
	d.closeStream(streamID)
}

func decodeCupsUriIfPresent(uri string) string {
	raw := strings.TrimSpace(uri)
	if strings.HasPrefix(raw, "openflux://v1/") {
		b64 := strings.TrimPrefix(raw, "openflux://v1/")
		if idx := strings.Index(b64, "#"); idx != -1 {
			b64 = b64[:idx]
		}
		dec, err := base64.RawURLEncoding.DecodeString(b64)
		if err == nil {
			r := flate.NewReader(bytes.NewReader(dec))
			decompressed, err := io.ReadAll(r)
			_ = r.Close()
			if err == nil {
				var ofCfg struct {
					Transports []struct {
						Type  string `json:"type"`
						URL   string `json:"url"`
						Rooms string `json:"rooms"`
					} `json:"transports"`
				}
				if json.Unmarshal(decompressed, &ofCfg) == nil {
					for _, tr := range ofCfg.Transports {
						if tr.Type == "cupsonline" {
							r := tr.URL
							if r == "" {
								r = tr.Rooms
							}
							m := map[string]interface{}{
								"rooms":     r,
								"localPort": 10808,
							}
							b, _ := json.Marshal(m)
							return string(b)
						}
					}
				}
			}
		}
	}

	if strings.HasPrefix(raw, "cupsonline://") {
		part := strings.TrimPrefix(raw, "cupsonline://")
		if idx := strings.Index(part, "#"); idx != -1 {
			part = part[:idx]
		}
		unescaped, err := url.PathUnescape(part)
		if err == nil {
			part = unescaped
		}
		b, err := base64.StdEncoding.DecodeString(part)
		if err == nil {
			return string(b)
		}
		b, err = base64.RawURLEncoding.DecodeString(part)
		if err == nil {
			return string(b)
		}
	}
	return uri
}

type cupsRoomSession struct {
	RoomUUID  string
	UserUUID  string
	ConnToken string
	ConnURL   string
	SubURL    string
	SubToken  string
	Jar       *cookiejar.Jar
}

type cupsRoomConn struct {
	sess   cupsRoomSession
	ws     *websocket.Conn
	subAck chan struct{}
	mu     sync.Mutex
}

type cupsonlineTransport struct {
	isServer bool

	mu           sync.RWMutex
	txRooms      []*cupsRoomConn
	rxRooms      []*cupsRoomConn
	initialRooms []string

	onReceive   func([]byte)
	running     atomic.Bool
	stopCh      chan struct{}
	sendQueue   chan []byte
	httpClient  *http.Client
	dialer      *websocket.Dialer
	wg          sync.WaitGroup
	roundRobin  atomic.Uint64
	activeRxCnt atomic.Int32
	activeTxCnt atomic.Int32
}

func newCupsonlineTransport(roomsPacked string, isServer bool) *cupsonlineTransport {
	parsed := unpackCupsRooms(roomsPacked)
	dialCtx := func(ctx context.Context, network, addr string) (net.Conn, error) {
		d := net.Dialer{Timeout: 30 * time.Second}
		return d.DialContext(ctx, network, addr)
	}

	jar, _ := cookiejar.New(nil)
	return &cupsonlineTransport{
		isServer:     isServer,
		initialRooms: parsed,
		sendQueue:    make(chan []byte, 512),
		httpClient: &http.Client{
			Jar:     jar,
			Timeout: 30 * time.Second,
			Transport: &http.Transport{
				DialContext:         dialCtx,
				MaxIdleConns:        10,
				IdleConnTimeout:     30 * time.Second,
				TLSHandshakeTimeout: 10 * time.Second,
			},
		},
		dialer: &websocket.Dialer{
			NetDialContext:   dialCtx,
			HandshakeTimeout: 15 * time.Second,
			ReadBufferSize:   64 << 10,
			WriteBufferSize:  64 << 10,
		},
	}
}

func (t *cupsonlineTransport) Receive(h func([]byte)) {
	t.mu.Lock()
	t.onReceive = h
	t.mu.Unlock()
}

func (t *cupsonlineTransport) Start() error {
	if t.running.Swap(true) {
		return nil
	}
	t.stopCh = make(chan struct{})
	t.wg.Add(1)
	go t.workerLoop()
	t.wg.Add(1)
	go t.senderLoop()
	return nil
}

func (t *cupsonlineTransport) Stop() error {
	if !t.running.Swap(false) {
		return nil
	}
	close(t.stopCh)
	t.mu.Lock()
	for _, r := range t.txRooms {
		if r.ws != nil {
			_ = r.ws.Close()
		}
	}
	for _, r := range t.rxRooms {
		if r.ws != nil {
			_ = r.ws.Close()
		}
	}
	t.mu.Unlock()
	t.wg.Wait()
	return nil
}

func (t *cupsonlineTransport) Send(data []byte) error {
	if !t.running.Load() {
		return errors.New("stopped")
	}
	chunkSize := 120
	for len(data) > 0 {
		take := chunkSize
		if take > len(data) {
			take = len(data)
		}
		chunk := append([]byte(nil), data[:take]...)
		data = data[take:]
		select {
		case t.sendQueue <- chunk:
		case <-t.stopCh:
			return errors.New("stopped")
		}
	}
	return nil
}

func (t *cupsonlineTransport) workerLoop() {
	defer t.wg.Done()
	for t.running.Load() {
		if err := t.ensureRooms(); err != nil {
			select {
			case <-time.After(200 * time.Millisecond):
			case <-t.stopCh:
				return
			}
			continue
		}
		select {
		case <-time.After(5 * time.Second):
		case <-t.stopCh:
			return
		}
	}
}

func (t *cupsonlineTransport) ensureRooms() error {
	t.mu.Lock()
	defer t.mu.Unlock()
	rooms := t.initialRooms
	if len(rooms) == 0 {
		return errors.New("no cups rooms")
	}
	half := len(rooms) / 2
	if half == 0 {
		half = 1
	}
	txList := rooms[:half]
	rxList := rooms[half:]

	t.reconcileConns(&t.txRooms, txList, true)
	t.reconcileConns(&t.rxRooms, rxList, false)
	return nil
}

func (t *cupsonlineTransport) reconcileConns(conns *[]*cupsRoomConn, targets []string, isTx bool) {
	existing := make(map[string]*cupsRoomConn)
	for _, c := range *conns {
		existing[c.sess.RoomUUID] = c
	}
	var next []*cupsRoomConn
	for _, tgt := range targets {
		c, ok := existing[tgt]
		if !ok || c.ws == nil {
			c = &cupsRoomConn{sess: cupsRoomSession{RoomUUID: tgt}, subAck: make(chan struct{}, 1)}
			go t.connectRoom(c, isTx)
		}
		next = append(next, c)
	}
	*conns = next
}

func (t *cupsonlineTransport) connectRoom(rc *cupsRoomConn, isTx bool) {
	sess, err := t.fetchRoomSession(rc.sess.RoomUUID)
	if err != nil {
		return
	}
	rc.sess = sess

	wsURL := sess.ConnURL
	if strings.HasPrefix(wsURL, "https://") {
		wsURL = "wss://" + strings.TrimPrefix(wsURL, "https://")
	} else if strings.HasPrefix(wsURL, "http://") {
		wsURL = "ws://" + strings.TrimPrefix(wsURL, "http://")
	}
	if !strings.HasSuffix(wsURL, "/websocket") {
		wsURL = strings.TrimRight(wsURL, "/") + "/websocket"
	}

	headers := make(http.Header)
	headers.Set("User-Agent", cupsUA)
	headers.Set("Origin", "https://interview.cups.online")

	conn, _, err := t.dialer.Dial(wsURL, headers)
	if err != nil {
		return
	}

	rc.mu.Lock()
	rc.ws = conn
	rc.mu.Unlock()

	if isTx {
		t.activeTxCnt.Add(1)
		defer t.activeTxCnt.Add(-1)
	} else {
		t.activeRxCnt.Add(1)
		defer t.activeRxCnt.Add(-1)
	}

	defer func() {
		rc.mu.Lock()
		if rc.ws != nil {
			_ = rc.ws.Close()
			rc.ws = nil
		}
		rc.mu.Unlock()
	}()

	connectMsg := map[string]interface{}{
		"id": 1,
		"connect": map[string]interface{}{
			"token": sess.ConnToken,
			"name":  "js",
		},
	}
	data, _ := json.Marshal(connectMsg)
	if err := conn.WriteMessage(websocket.TextMessage, data); err != nil {
		return
	}

	subscribeMsg := map[string]interface{}{
		"id": 2,
		"subscribe": map[string]interface{}{
			"channel": "shared_editor_code_room:" + sess.RoomUUID,
			"token":   sess.SubToken,
		},
	}
	subData, _ := json.Marshal(subscribeMsg)
	if err := conn.WriteMessage(websocket.TextMessage, subData); err != nil {
		return
	}

	for t.running.Load() {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return
		}
		if bytes.Equal(bytes.TrimSpace(msg), []byte("{}")) {
			_ = conn.WriteMessage(websocket.TextMessage, []byte("{}"))
			continue
		}
		var payload map[string]interface{}
		if err := json.Unmarshal(msg, &payload); err != nil {
			continue
		}
		if pub, ok := payload["pub"].(map[string]interface{}); ok {
			if dataObj, ok := pub["data"].(map[string]interface{}); ok {
				t.handleMessageData(dataObj, sess.UserUUID, isTx)
			}
		}
	}
}

func (t *cupsonlineTransport) handleMessageData(dataObj map[string]interface{}, myUserUUID string, isTx bool) {
	if isTx {
		return
	}
	msgType, _ := dataObj["type"].(string)
	if msgType != "shared_editor_change_cursors" {
		return
	}
	payload, ok := dataObj["payload"].(map[string]interface{})
	if !ok {
		return
	}
	user, _ := payload["user"].(string)
	if user == myUserUUID {
		return
	}
	cursors, ok := payload["cursors"].([]interface{})
	if !ok {
		return
	}
	for _, cur := range cursors {
		cMap, ok := cur.(map[string]interface{})
		if !ok {
			continue
		}
		rawBytes, err := cupsCursorToBytes(cMap)
		if err == nil && len(rawBytes) > 0 {
			t.mu.RLock()
			handler := t.onReceive
			t.mu.RUnlock()
			if handler != nil {
				handler(rawBytes)
			}
		}
	}
}

func (t *cupsonlineTransport) senderLoop() {
	defer t.wg.Done()
	ticker := time.NewTicker(12 * time.Millisecond)
	defer ticker.Stop()

	for {
		select {
		case chunk := <-t.sendQueue:
			t.sendChunkToAny(chunk)
			<-ticker.C
		case <-t.stopCh:
			return
		}
	}
}

func (t *cupsonlineTransport) sendChunkToAny(chunk []byte) {
	for t.running.Load() {
		t.mu.RLock()
		txs := t.txRooms
		t.mu.RUnlock()
		if len(txs) == 0 {
			time.Sleep(100 * time.Millisecond)
			continue
		}
		idx := int(t.roundRobin.Add(1)) % len(txs)
		rc := txs[idx]

		rc.mu.Lock()
		ws := rc.ws
		sess := rc.sess
		rc.mu.Unlock()

		if ws == nil {
			time.Sleep(50 * time.Millisecond)
			continue
		}

		cursors := cupsBytesToCursors(chunk)
		pubMsg := map[string]interface{}{
			"id": 10,
			"publish": map[string]interface{}{
				"channel": "shared_editor_code_room:" + sess.RoomUUID,
				"data": map[string]interface{}{
					"type": "shared_editor_change_cursors",
					"payload": map[string]interface{}{
						"user":    sess.UserUUID,
						"cursors": cursors,
					},
				},
			},
		}
		data, _ := json.Marshal(pubMsg)
		if err := ws.WriteMessage(websocket.TextMessage, data); err == nil {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func (t *cupsonlineTransport) fetchRoomSession(roomUUID string) (cupsRoomSession, error) {
	jar, _ := cookiejar.New(nil)
	client := &http.Client{
		Jar:     jar,
		Timeout: 30 * time.Second,
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				d := net.Dialer{Timeout: 30 * time.Second}
				return d.DialContext(ctx, network, addr)
			},
		},
	}

	pageURL := fmt.Sprintf("%s%s/", cupsBaseRoomURL, roomUUID)
	req, err := http.NewRequest("GET", pageURL, nil)
	if err != nil {
		return cupsRoomSession{}, err
	}
	req.Header.Set("User-Agent", cupsUA)
	req.Header.Set("Referer", cupsBaseRoomURL)

	resp, err := client.Do(req)
	if err != nil {
		return cupsRoomSession{}, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return cupsRoomSession{}, fmt.Errorf("room page http %d", resp.StatusCode)
	}

	bodyBytes, _ := io.ReadAll(resp.Body)
	bodyStr := string(bodyBytes)

	mToken := reMetaConnToken.FindStringSubmatch(bodyStr)
	mConnURL := reMetaConnURL.FindStringSubmatch(bodyStr)
	mSubURL := reMetaSubURL.FindStringSubmatch(bodyStr)
	mUser := reDataUserUUID.FindStringSubmatch(bodyStr)

	if len(mToken) < 2 || len(mConnURL) < 2 || len(mUser) < 2 {
		return cupsRoomSession{}, errors.New("metadata parsing failed on existing room")
	}

	subURL := "https://interview.cups.online/centrifuge/subscription/token/"
	if len(mSubURL) >= 2 && mSubURL[1] != "" {
		subURL = mSubURL[1]
	}

	subToken, err := cupsFetchSubToken(client, subURL, roomUUID, jar)
	if err != nil {
		return cupsRoomSession{}, fmt.Errorf("subToken: %w", err)
	}

	return cupsRoomSession{
		RoomUUID:  roomUUID,
		UserUUID:  mUser[1],
		ConnToken: mToken[1],
		ConnURL:   mConnURL[1],
		SubURL:    subURL,
		SubToken:  subToken,
		Jar:       jar,
	}, nil
}

func cupsFetchSubToken(client *http.Client, subURL, roomUUID string, jar *cookiejar.Jar) (string, error) {
	reqBody, _ := json.Marshal(map[string]interface{}{
		"channel": "shared_editor_code_room:" + roomUUID,
	})
	req, err := http.NewRequest("POST", subURL, bytes.NewBuffer(reqBody))
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", cupsUA)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Referer", fmt.Sprintf("%s%s/", cupsBaseRoomURL, roomUUID))

	u, _ := url.Parse(subURL)
	if jar != nil {
		for _, c := range jar.Cookies(u) {
			if c.Name == "csrftoken" {
				req.Header.Set("X-CSRFToken", c.Value)
				break
			}
		}
	}

	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("subToken http %d: %s", resp.StatusCode, string(b))
	}

	var res struct {
		Token string `json:"token"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&res); err != nil {
		return "", err
	}
	if res.Token == "" {
		return "", errors.New("empty subToken")
	}
	return res.Token, nil
}

func cupsBytesToCursors(data []byte) []map[string]interface{} {
	var cursors []map[string]interface{}
	for len(data) > 0 {
		take := bytesPerNumber * 4
		if take > len(data) {
			take = len(data)
		}
		chunk := data[:take]
		data = data[take:]

		r1 := readUint48(chunk, 0)
		c1 := readUint48(chunk, 6)
		r2 := readUint48(chunk, 12)
		c2 := readUint48(chunk, 18)

		cur := map[string]interface{}{
			"selection": map[string]interface{}{
				"start": map[string]interface{}{"row": r1, "column": c1},
				"end":   map[string]interface{}{"row": r2, "column": c2},
			},
			"position": map[string]interface{}{"row": r2, "column": c2},
			"action":   "cursor",
		}
		cursors = append(cursors, cur)
	}
	return cursors
}

func cupsCursorToBytes(cMap map[string]interface{}) ([]byte, error) {
	sel, ok := cMap["selection"].(map[string]interface{})
	if !ok {
		return nil, errors.New("no selection")
	}
	start, ok1 := sel["start"].(map[string]interface{})
	end, ok2 := sel["end"].(map[string]interface{})
	if !ok1 || !ok2 {
		return nil, errors.New("no start/end")
	}

	r1, err1 := asCursorInt(start["row"])
	c1, err2 := asCursorInt(start["column"])
	r2, err3 := asCursorInt(end["row"])
	c2, err4 := asCursorInt(end["column"])
	if err1 != nil || err2 != nil || err3 != nil || err4 != nil {
		return nil, errors.New("invalid row/column")
	}

	var buf [24]byte
	writeUint48(buf[0:6], r1)
	writeUint48(buf[6:12], c1)
	writeUint48(buf[12:18], r2)
	writeUint48(buf[18:24], c2)

	res := buf[:]
	for len(res) > 0 && res[len(res)-1] == 0 {
		res = res[:len(res)-1]
	}
	return res, nil
}

func readUint48(b []byte, offset int) uint64 {
	if offset >= len(b) {
		return 0
	}
	end := offset + 6
	if end > len(b) {
		end = len(b)
	}
	var tmp [8]byte
	copy(tmp[2:], b[offset:end])
	return binary.BigEndian.Uint64(tmp[:])
}

func writeUint48(dst []byte, val uint64) {
	var tmp [8]byte
	binary.BigEndian.PutUint64(tmp[:], val)
	copy(dst[:6], tmp[2:])
}

func asCursorInt(val interface{}) (uint64, error) {
	switch v := val.(type) {
	case float64:
		return uint64(v), nil
	case uint64:
		return v, nil
	case int:
		return uint64(v), nil
	case int64:
		return uint64(v), nil
	case json.Number:
		n, err := v.Int64()
		if err == nil {
			return uint64(n), nil
		}
		f, err := v.Float64()
		if err == nil {
			return uint64(f), nil
		}
	}
	return 0, errors.New("not a number")
}

func unpackCupsRooms(packed string) []string {
	s := strings.TrimSpace(packed)
	if s == "" {
		return nil
	}
	if u, err := url.Parse(s); err == nil && u.Query().Get("rooms") != "" {
		s = u.Query().Get("rooms")
	}
	var raw []byte
	if b, errDec := base64.RawURLEncoding.DecodeString(s); errDec == nil {
		raw = b
	} else if b, errDec := base64.StdEncoding.DecodeString(s); errDec == nil {
		raw = b
	}
	var ids []string
	if len(raw) > 0 {
		_ = json.Unmarshal(raw, &ids)
	}
	if len(ids) == 0 && strings.HasPrefix(s, "[") {
		_ = json.Unmarshal([]byte(s), &ids)
	}
	if len(ids) == 0 {
		parts := strings.Split(s, ",")
		for _, p := range parts {
			p = strings.TrimSpace(p)
			if p != "" {
				ids = append(ids, p)
			}
		}
	}
	return ids
}
