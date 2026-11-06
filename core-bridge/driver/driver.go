package driver

import (
	"context"
)

type Driver interface {
	Name() string
	Start(ctx context.Context, configJson string, tunFd int, logPath string) error
	Stop() error
	IsRunning() bool
}

type Stats struct {
	RxBytes       int64  `json:"rx_bytes"`
	TxBytes       int64  `json:"tx_bytes"`
	RxSpeed       int64  `json:"rx_speed"`
	TxSpeed       int64  `json:"tx_speed"`
	ActiveStreams int    `json:"active_streams"`
	LastError     string `json:"last_error,omitempty"`
}

