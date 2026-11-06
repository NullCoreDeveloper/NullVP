package driver

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"

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

	normConfigJson, err := normalizeVkTurnConfig(configJson)
	if err != nil {
		return fmt.Errorf("vkturn config error: %w", err)
	}

	configB64 := base64.StdEncoding.EncodeToString([]byte(normConfigJson))
	configDir, _ := os.UserConfigDir()
	if configDir == "" {
		configDir = os.TempDir()
	}

	err = vkturncore.StartVkTurnClient(configB64, logPath, configDir)
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

func normalizeVkTurnConfig(raw string) (string, error) {
	raw = strings.TrimSpace(raw)
	if strings.HasPrefix(strings.ToLower(raw), "freeturn://") || strings.HasPrefix(strings.ToLower(raw), "vkturn://") {
		raw = decodeVkTurnUri(raw)
	}

	var m map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		return "", fmt.Errorf("invalid json: %w", err)
	}

	// Check if freeturn key contains embedded uri
	if ft, ok := m["freeturn"].(string); ok && ft != "" {
		decoded := decodeVkTurnUri(ft)
		var ftMap map[string]interface{}
		if err := json.Unmarshal([]byte(decoded), &ftMap); err == nil {
			for k, v := range ftMap {
				if _, exists := m[k]; !exists {
					m[k] = v
				}
			}
		}
	}

	server := getString(m, "server", "address")
	port := getInt(m, "port")
	peer := getString(m, "peer")

	if (server == "" || port == 0) && peer != "" {
		host, portStr, err := net.SplitHostPort(peer)
		if err == nil {
			if server == "" {
				server = host
			}
			if port == 0 {
				port, _ = strconv.Atoi(portStr)
			}
		} else {
			if server == "" {
				server = peer
			}
		}
	}

	// Fallback to parsing WireGuard config if still missing
	if server == "" || port == 0 {
		wgStr := getString(m, "wgConf", "wg")
		if wgStr != "" {
			for _, line := range strings.Split(wgStr, "\n") {
				line = strings.TrimSpace(line)
				if strings.HasPrefix(strings.ToLower(line), "endpoint") {
					parts := strings.SplitN(line, "=", 2)
					if len(parts) == 2 {
						ep := strings.TrimSpace(parts[1])
						h, pStr, err := net.SplitHostPort(ep)
						if err == nil && h != "127.0.0.1" && h != "localhost" {
							if server == "" {
								server = h
							}
							if port == 0 {
								port, _ = strconv.Atoi(pStr)
							}
						}
					}
				}
			}
		}
	}

	if server == "" {
		return "", fmt.Errorf("server address is missing in config")
	}
	if port == 0 {
		port = 443
	}

	vkLink := getString(m, "vkLink", "vk")
	streams := getInt(m, "streams", "n")
	if streams <= 0 {
		streams = 10
	}

	obfProfile := getString(m, "obfProfile", "obf")
	obfKey := getString(m, "obfKey", "key")
	targetProto := getString(m, "targetProtocol", "protocol")
	if targetProto == "" {
		if getString(m, "wg", "wgConf") != "" {
			targetProto = "wireguard"
		} else {
			targetProto = "tcp"
		}
	}

	localPort := getInt(m, "localPort")

	clientCfg := map[string]interface{}{
		"server":         server,
		"port":           port,
		"targetProtocol": targetProto,
		"vkLink":         vkLink,
		"vkToken":        getString(m, "vkToken", "token"),
		"streams":        streams,
		"vkAppId":        getString(m, "vkAppId", "appId"),
		"vkAppSecret":    getString(m, "vkAppSecret", "appSecret"),
		"clientId":       getString(m, "clientId", "cid"),
		"clientPassword": getString(m, "clientPassword", "password"),
		"localPort":      localPort,
		"obfProfile":     obfProfile,
		"obfKey":         obfKey,
	}

	out, err := json.Marshal(clientCfg)
	if err != nil {
		return "", err
	}
	return string(out), nil
}

func decodeVkTurnUri(uri string) string {
	trimmed := strings.TrimSpace(uri)
	var payload string
	if strings.HasPrefix(strings.ToLower(trimmed), "freeturn://") {
		payload = trimmed[11:]
	} else if strings.HasPrefix(strings.ToLower(trimmed), "vkturn://") {
		payload = trimmed[9:]
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

func getString(m map[string]interface{}, keys ...string) string {
	for _, k := range keys {
		if v, ok := m[k]; ok {
			if s, ok := v.(string); ok && s != "" {
				return s
			}
		}
	}
	return ""
}

func getInt(m map[string]interface{}, keys ...string) int {
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
