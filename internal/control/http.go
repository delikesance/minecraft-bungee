package control

import (
	"context"
	"errors"
	"net"
	"net/http"
	"os/signal"
	"syscall"
	"time"
)

// Listen cancels streams and waits for handlers before process shutdown.
func Listen(address string, handler http.Handler) error {
	return ListenAll(map[string]http.Handler{address: handler})
}

// ListenAll shares one graceful shutdown across the public panel and private bridge.
func ListenAll(handlers map[string]http.Handler) error {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	result := make(chan error, len(handlers))
	servers := []*http.Server{}
	for address, handler := range handlers {
		server := &http.Server{Addr: address, Handler: handler, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 90 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16384, BaseContext: func(net.Listener) context.Context { return ctx }}
		servers = append(servers, server)
		go func() { result <- server.ListenAndServe() }()
	}
	var failure error
	select {
	case failure = <-result:
		stop()
	case <-ctx.Done():
	}
	deadline, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	for _, server := range servers {
		if err := server.Shutdown(deadline); err != nil {
			server.Close()
			if failure == nil {
				failure = err
			}
		}
	}
	if errors.Is(failure, http.ErrServerClosed) {
		return nil
	}
	return failure
}
