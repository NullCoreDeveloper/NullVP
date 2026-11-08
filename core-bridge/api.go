package nullvpcore

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"sync"

	"github.com/2dust/AndroidLibXrayLite/vkturncore"
	"nullvp-core/driver"
)

var (
	mu            sync.Mutex
	currentDriver driver.Driver
	cancelFunc    context.CancelFunc
)

type CaptchaHandler interface {
	OpenCaptcha(url string)
}

type vkCaptchaAdapter struct {
	handler CaptchaHandler
}

func (a *vkCaptchaAdapter) OpenCaptcha(url string) {
	if a.handler != nil {
		a.handler.OpenCaptcha(url)
	}
}

func SetCaptchaHandler(h CaptchaHandler) {
	vkturncore.SetCaptchaHandler(&vkCaptchaAdapter{handler: h})
}

func SubmitCaptchaToken(token string) {
	vkturncore.SubmitCaptchaToken(token)
}

// StartProfile starts the core with the specified protocol and config
func StartProfile(protocolType string, configJsonBase64 string, tunFd int, logPath string) error {
	mu.Lock()
	defer mu.Unlock()

	// Stop any existing driver
	if currentDriver != nil && currentDriver.IsRunning() {
		_ = currentDriver.Stop()
		if cancelFunc != nil {
			cancelFunc()
		}
	}

	decodedBytes, err := base64.StdEncoding.DecodeString(configJsonBase64)
	if err != nil {
		return fmt.Errorf("failed to decode config: %w", err)
	}
	configJson := string(decodedBytes)

	var d driver.Driver
	switch protocolType {
	case "MASTER_DNS", "master_dns", "mdns":
		d = driver.NewMdnsDriver()
	case "VK_TURN", "vk_turn", "vkturn", "freeturn":
		d = driver.NewVkTurnDriver()
	case "MAILRU", "mailru", "openflux", "OPENFLUX":
		d = driver.NewMailruDriver()
	case "AVITO", "avito":
		d = driver.NewAvitoDriver()
	case "CUPSONLINE", "cupsonline":
		d = driver.NewCupsDriver()
	default:
		return fmt.Errorf("unsupported protocol type: %s", protocolType)
	}

	ctx, cancel := context.WithCancel(context.Background())
	err = d.Start(ctx, configJson, tunFd, logPath)
	if err != nil {
		cancel()
		return err
	}

	currentDriver = d
	cancelFunc = cancel
	return nil
}

// StopProfile stops the currently running profile
func StopProfile() error {
	mu.Lock()
	defer mu.Unlock()

	if cancelFunc != nil {
		cancelFunc()
		cancelFunc = nil
	}

	if currentDriver != nil {
		err := currentDriver.Stop()
		currentDriver = nil
		return err
	}
	return nil
}

// IsRunning returns whether any driver is currently active
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return currentDriver != nil && currentDriver.IsRunning()
}

// GetStats returns current status JSON
func GetStats() string {
	mu.Lock()
	defer mu.Unlock()

	st := driver.Stats{}
	if currentDriver != nil && currentDriver.IsRunning() {
		if vk, ok := currentDriver.(*driver.VkTurnDriver); ok {
			st.ActiveStreams = vk.ActiveStreams()
			st.LastError = vk.LastError()
		} else if mr, ok := currentDriver.(*driver.MailruDriver); ok {
			st.ActiveStreams = mr.ActiveStreams()
			st.LastError = mr.LastError()
		}
	}

	bytes, _ := json.Marshal(st)
	return string(bytes)
}

func Wake() {
	// Wake-up kick for socket/network recovery
}

