package driver

import "encoding/binary"

const (
	FrameOpen    byte = 1
	FrameData    byte = 2
	FrameClose   byte = 3
	FrameOpenOK  byte = 4
	FrameOpenErr byte = 5
	FrameAck     byte = 6
)

const headerLen = 9

type Frame struct {
	Type     byte
	StreamID uint32
	Payload  []byte
}

func Encode(dst []byte, f Frame) []byte {
	var h [headerLen]byte
	h[0] = f.Type
	binary.BigEndian.PutUint32(h[1:], f.StreamID)
	binary.BigEndian.PutUint32(h[5:], uint32(len(f.Payload)))
	dst = append(dst, h[:]...)
	return append(dst, f.Payload...)
}

func TakeFrame(buf []byte) (Frame, []byte, bool) {
	if len(buf) < headerLen {
		return Frame{}, buf, false
	}
	n := binary.BigEndian.Uint32(buf[5:])
	if uint32(len(buf)-headerLen) < n {
		return Frame{}, buf, false
	}
	f := Frame{
		Type:     buf[0],
		StreamID: binary.BigEndian.Uint32(buf[1:]),
		Payload:  append([]byte(nil), buf[headerLen:headerLen+n]...),
	}
	return f, buf[headerLen+n:], true
}
