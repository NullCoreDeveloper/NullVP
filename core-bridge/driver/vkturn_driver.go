package driver

import (
	"context"
	"encoding/base64"
	"fmt"
	"os"

	"github.com/2dust/AndroidLibXrayLite/vkturncore"
)

type VkTurnDriver struct{}

func NewVkTurnDriver() *VkTurnDriver {
	return &VkTurnDriver{}
}

func (d *VkTurnDriver) Name() string {
	return "vk_turn"
}

func (d *VkTurnDriver) Start(ctx context.Context, configJson string, tunFd int, logPath string) error {
	if vkturncore.IsVkTurnClientRunning() {
		_ = vkturncore.StopVkTurnClient()
	}

	configB64 := base64.StdEncoding.EncodeToString([]byte(configJson))
	configDir, _ := os.UserConfigDir()
	if configDir == "" {
		configDir = os.TempDir()
	}

	err := vkturncore.StartVkTurnClient(configB64, logPath, configDir)
	if err != nil {
		return fmt.Errorf("vkturn start error: %w", err)
	}
	return nil
}

func (d *VkTurnDriver) Stop() error {
	return vkturncore.StopVkTurnClient()
}

func (d *VkTurnDriver) IsRunning() bool {
	return vkturncore.IsVkTurnClientRunning()
}

func (d *VkTurnDriver) ActiveStreams() int {
	return vkturncore.GetVkTurnActiveStreams()
}

func (d *VkTurnDriver) LastError() string {
	return vkturncore.GetVkTurnLastError()
}

