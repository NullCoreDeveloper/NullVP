package driver

import (
	"context"
	"encoding/base64"
	"fmt"
	"os"

	"masterdnsvpn-go/mdnscore"
)

type MdnsDriver struct{}

func NewMdnsDriver() *MdnsDriver {
	return &MdnsDriver{}
}

func (d *MdnsDriver) Name() string {
	return "master_dns"
}

func (d *MdnsDriver) Start(ctx context.Context, configJson string, tunFd int, logPath string) error {
	if mdnscore.IsMdnsClientRunning() {
		_ = mdnscore.StopMdnsClient()
	}

	configB64 := base64.StdEncoding.EncodeToString([]byte(configJson))
	configDir, _ := os.UserConfigDir()
	if configDir == "" {
		configDir = os.TempDir()
	}

	err := mdnscore.StartMdnsClient(configB64, logPath, configDir)
	if err != nil {
		return fmt.Errorf("mdns start error: %w", err)
	}
	return nil
}

func (d *MdnsDriver) Stop() error {
	return mdnscore.StopMdnsClient()
}

func (d *MdnsDriver) IsRunning() bool {
	return mdnscore.IsMdnsClientRunning()
}

