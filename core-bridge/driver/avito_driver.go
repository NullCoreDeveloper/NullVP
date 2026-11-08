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
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

var avitoBaseURL = "https://code.avito.ru"

const (
	avitoUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)

type AvitoDriver struct {
	mu            sync.Mutex
	running       atomic.Bool
	connected     atomic.Bool
	lastErr       string
	activeStreams atomic.Int32

	cancel      context.CancelFunc
	ctx         context.Context
	socksLn     net.Listener
	transport   *avitoTransport
	activeConns map[uint32]net.Conn
	streamSeed  atomic.Uint32
	rxBuf       []byte
}

func NewAvitoDriver() *AvitoDriver {
	return &AvitoDriver{
		activeConns: make(map[uint32]net.Conn),
	}
}

func (d *AvitoDriver) Name() string {
	return "avito"
}

func (d *AvitoDriver) IsRunning() bool {
	return d.running.Load()
}

func (d *AvitoDriver) ActiveStreams() int {
	return int(d.activeStreams.Load())
}

func (d *AvitoDriver) LastError() string {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.lastErr
}

func (d *AvitoDriver) Start(ctx context.Context, configJson string, tunFd int, logPath string) error {
	if d.running.Swap(true) {
		_ = d.Stop()
	}

	configJson = decodeAvitoUriIfPresent(configJson)

	var m map[string]interface{}
	if err := json.Unmarshal([]byte(configJson), &m); err != nil {
		d.running.Store(false)
		return fmt.Errorf("avito invalid json: %w", err)
	}

	rooms := getStringField(m, "rooms")
	localPort := getIntField(m, "localPort", "port")
	if localPort <= 0 {
		localPort = 10808
	}

	d.ctx, d.cancel = context.WithCancel(ctx)
	d.transport = newAvitoTransport(rooms, false)
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
		return fmt.Errorf("start avito transport: %w", err)
	}

	go d.socksAcceptLoop(ln)
	return nil
}

func (d *AvitoDriver) safeSend(data []byte) error {
	d.mu.Lock()
	tr := d.transport
	running := d.running.Load()
	d.mu.Unlock()
	if tr == nil || !running {
		return errors.New("avito stopped")
	}
	return tr.Send(data)
}

func (d *AvitoDriver) Stop() error {
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

func (d *AvitoDriver) handleRx(data []byte) {
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

func (d *AvitoDriver) dispatch(f Frame) {
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

func (d *AvitoDriver) closeStream(streamID uint32) {
	d.mu.Lock()
	c := d.activeConns[streamID]
	delete(d.activeConns, streamID)
	d.mu.Unlock()
	if c != nil {
		_ = c.Close()
		d.activeStreams.Add(-1)
	}
}

func (d *AvitoDriver) socksAcceptLoop(ln net.Listener) {
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

func (d *AvitoDriver) handleSocksConn(conn net.Conn) {
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

func decodeAvitoUriIfPresent(uri string) string {
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
						if tr.Type == "avito" {
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

	if strings.HasPrefix(raw, "avito://") {
		part := strings.TrimPrefix(raw, "avito://")
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

type avitoRoomInfo struct {
	AuthorID string
	RoomID   string
}

type avitoRoomConn struct {
	info    avitoRoomInfo
	ws      *websocket.Conn
	subAck  chan struct{}
	version int
	seq     int
	mu      sync.Mutex
}

type avitoTransport struct {
	isServer bool

	mu           sync.RWMutex
	txRooms      []*avitoRoomConn
	rxRooms      []*avitoRoomConn
	initialRooms []avitoRoomInfo

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

func newAvitoTransport(roomsPacked string, isServer bool) *avitoTransport {
	parsed := unpackAvitoRooms(roomsPacked)
	dialCtx := func(ctx context.Context, network, addr string) (net.Conn, error) {
		d := net.Dialer{Timeout: 30 * time.Second}
		return d.DialContext(ctx, network, addr)
	}

	return &avitoTransport{
		isServer:     isServer,
		initialRooms: parsed,
		sendQueue:    make(chan []byte, 256),
		httpClient: &http.Client{
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
			ReadBufferSize:   256 << 10,
			WriteBufferSize:  256 << 10,
		},
	}
}

func (t *avitoTransport) Receive(h func([]byte)) {
	t.mu.Lock()
	t.onReceive = h
	t.mu.Unlock()
}

func (t *avitoTransport) Start() error {
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

func (t *avitoTransport) Stop() error {
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

func (t *avitoTransport) Send(data []byte) error {
	if !t.running.Load() {
		return errors.New("stopped")
	}
	encoded := make([]byte, base64.StdEncoding.EncodedLen(len(data)))
	base64.StdEncoding.Encode(encoded, data)

	chunkSize := 256 * 1024
	for len(encoded) > 0 {
		take := chunkSize
		if take > len(encoded) {
			take = len(encoded)
		}
		chunk := encoded[:take]
		encoded = encoded[take:]

		select {
		case t.sendQueue <- chunk:
		case <-t.stopCh:
			return errors.New("stopped")
		}
	}
	return nil
}

func (t *avitoTransport) workerLoop() {
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

func (t *avitoTransport) ensureRooms() error {
	t.mu.Lock()
	defer t.mu.Unlock()

	rooms := t.initialRooms
	if len(rooms) == 0 {
		return errors.New("no avito rooms")
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

func (t *avitoTransport) reconcileConns(conns *[]*avitoRoomConn, targets []avitoRoomInfo, isTx bool) {
	existing := make(map[string]*avitoRoomConn)
	for _, c := range *conns {
		existing[c.info.RoomID] = c
	}
	var next []*avitoRoomConn
	for _, tgt := range targets {
		c, ok := existing[tgt.RoomID]
		if !ok || c.ws == nil {
			c = &avitoRoomConn{info: tgt, subAck: make(chan struct{}, 1)}
			go t.connectRoom(c, isTx)
		}
		next = append(next, c)
	}
	*conns = next
}

func (t *avitoTransport) connectRoom(rc *avitoRoomConn, isTx bool) {
	wsURL := fmt.Sprintf("wss://code.avito.ru/sharedb?authorID=%s&roomID=%s", rc.info.AuthorID, rc.info.RoomID)
	headers := make(http.Header)
	headers.Set("User-Agent", avitoUA)
	headers.Set("Origin", "https://code.avito.ru")

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

	subMsg := map[string]interface{}{"a": "sub", "c": "rooms", "d": rc.info.RoomID}
	subBytes, _ := json.Marshal(subMsg)
	_ = conn.WriteMessage(websocket.TextMessage, subBytes)

	for t.running.Load() {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return
		}
		var payload map[string]interface{}
		if err := json.Unmarshal(msg, &payload); err != nil {
			continue
		}
		action, _ := payload["a"].(string)
		if action == "ack" || action == "s" {
			select {
			case rc.subAck <- struct{}{}:
			default:
			}
			if v, ok := payload["v"].(float64); ok {
				rc.version = int(v)
			}
		}
		if !isTx && action == "op" {
			if opList, ok := payload["op"].([]interface{}); ok {
				t.handleOps(opList)
			}
		}
	}
}

func (t *avitoTransport) handleOps(opList []interface{}) {
	for _, item := range opList {
		m, ok := item.(map[string]interface{})
		if !ok {
			continue
		}
		text, _ := m["i"].(string)
		if text == "" {
			continue
		}
		raw, err := base64.StdEncoding.DecodeString(text)
		if err == nil && len(raw) > 0 {
			t.mu.RLock()
			handler := t.onReceive
			t.mu.RUnlock()
			if handler != nil {
				handler(raw)
			}
		}
	}
}

func (t *avitoTransport) senderLoop() {
	defer t.wg.Done()
	for {
		select {
		case chunk := <-t.sendQueue:
			t.sendChunkToAny(chunk)
		case <-t.stopCh:
			return
		}
	}
}

func (t *avitoTransport) sendChunkToAny(chunk []byte) {
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
		rc.mu.Unlock()

		if ws == nil {
			time.Sleep(50 * time.Millisecond)
			continue
		}

		rc.seq++
		rc.version++
		opMsg := map[string]interface{}{
			"a":   "op",
			"c":   "rooms",
			"d":   rc.info.RoomID,
			"v":   rc.version,
			"seq": rc.seq,
			"src": "client",
			"op": []interface{}{
				map[string]interface{}{
					"p": 0,
					"i": string(chunk),
				},
			},
		}
		data, _ := json.Marshal(opMsg)
		if err := ws.WriteMessage(websocket.TextMessage, data); err == nil {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func unpackAvitoRooms(packed string) []avitoRoomInfo {
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
	var res []avitoRoomInfo
	for _, id := range ids {
		if strings.Contains(id, "/") {
			kv := strings.SplitN(id, "/", 2)
			res = append(res, avitoRoomInfo{AuthorID: kv[0], RoomID: kv[1]})
		} else {
			res = append(res, avitoRoomInfo{AuthorID: "Developer", RoomID: id})
		}
	}
	return res
}

