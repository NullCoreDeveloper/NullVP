package main

import (
	"bufio"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"strings"
	"syscall"

	nullvpcore "nullvp-core"
)

func main() {
	daemonMode := flag.Bool("daemon", false, "Run as elevated background daemon")
	socketPath := flag.String("socket", "/tmp/nullvp-core.sock", "UNIX socket path for IPC")
	socksPort := flag.Int("socks-port", 10808, "Local SOCKS5 proxy port")
	httpPort := flag.Int("http-port", 10809, "Local HTTP proxy port")
	flag.Parse()

	log.Printf("[NULLVP-CORE] Starting (daemon=%v, socks=%d, http=%d)", *daemonMode, *socksPort, *httpPort)

	if *daemonMode {
		runDaemon(*socketPath)
	} else {
		log.Println("[NULLVP-CORE] Running in interactive CLI mode")
		sigChan := make(chan os.Signal, 1)
		signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
		<-sigChan
		_ = nullvpcore.StopProfile()
		log.Println("[NULLVP-CORE] Stopped")
	}
}

func runDaemon(sockPath string) {
	_ = os.Remove(sockPath)

	listener, err := net.Listen("unix", sockPath)
	if err != nil {
		log.Fatalf("[DAEMON] Failed to bind UNIX socket %s: %v", sockPath, err)
	}
	defer func() {
		_ = listener.Close()
		_ = os.Remove(sockPath)
	}()

	// Set permissions so unprivileged GUI user can read/write to the socket
	_ = os.Chmod(sockPath, 0666)

	log.Printf("[DAEMON] Listening on %s", sockPath)

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		<-sigChan
		log.Println("[DAEMON] Shutting down...")
		_ = nullvpcore.StopProfile()
		_ = listener.Close()
		_ = os.Remove(sockPath)
		os.Exit(0)
	}()

	for {
		conn, err := listener.Accept()
		if err != nil {
			break
		}
		go handleIpcConnection(conn)
	}
}

func handleIpcConnection(conn net.Conn) {
	defer conn.Close()
	scanner := bufio.NewScanner(conn)

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}

		parts := strings.SplitN(line, " ", 3)
		cmd := strings.ToUpper(parts[0])

		switch cmd {
		case "START":
			if len(parts) < 3 {
				fmt.Fprintf(conn, "ERR invalid START command arguments\n")
				continue
			}
			protocolType := parts[1]
			configBase64 := parts[2]
			err := nullvpcore.StartProfile(protocolType, configBase64, 0, "")
			if err != nil {
				fmt.Fprintf(conn, "ERR %v\n", err)
			} else {
				fmt.Fprintf(conn, "OK started %s\n", protocolType)
			}

		case "STOP":
			err := nullvpcore.StopProfile()
			if err != nil {
				fmt.Fprintf(conn, "ERR %v\n", err)
			} else {
				fmt.Fprintf(conn, "OK stopped\n")
			}

		case "STATUS":
			stats := nullvpcore.GetStats()
			fmt.Fprintf(conn, "OK %s\n", stats)

		case "SHUTDOWN":
			fmt.Fprintf(conn, "OK bye\n")
			_ = nullvpcore.StopProfile()
			os.Exit(0)

		default:
			fmt.Fprintf(conn, "ERR unknown command\n")
		}
	}
}

