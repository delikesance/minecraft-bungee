package control

import (
	"bufio"
	"bytes"
	"context"
	"crypto/subtle"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

var IDs = []string{"velocity", "lobby", "creative", "survival"}

func Valid(id string) bool {
	for _, s := range IDs {
		if s == id {
			return true
		}
	}
	return false
}
func Secret(path string) (string, error) {
	b, e := os.ReadFile(path)
	if e != nil {
		return "", e
	}
	s := strings.TrimSpace(string(b))
	if len(s) < 32 {
		return "", errors.New("secret must contain at least 32 characters")
	}
	return s, nil
}
func Equal(a, b string) bool { return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1 }

type Status struct {
	ID        string `json:"id"`
	State     string `json:"state"`
	Health    string `json:"health"`
	StartedAt string `json:"startedAt,omitempty"`
	Commands  bool   `json:"commands"`
}
type Docker struct {
	HTTP    *http.Client
	Project string
}

func NewDocker(socket, project string) *Docker {
	return &Docker{HTTP: &http.Client{Transport: &http.Transport{DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "unix", socket)
	}}, Timeout: 70 * time.Second}, Project: project}
}
func (d *Docker) request(ctx context.Context, method, path string) (*http.Response, error) {
	req, e := http.NewRequestWithContext(ctx, method, "http://docker/v1.44"+path, nil)
	if e != nil {
		return nil, e
	}
	r, e := d.HTTP.Do(req)
	if e != nil {
		return nil, errors.New("Docker unavailable")
	}
	if r.StatusCode >= 300 {
		r.Body.Close()
		return nil, fmt.Errorf("Docker returned %d", r.StatusCode)
	}
	return r, nil
}
func (d *Docker) container(ctx context.Context, id string) (string, error) {
	if !Valid(id) {
		return "", errors.New("unknown server")
	}
	filter, _ := json.Marshal(map[string][]string{"label": {"win.vampyuyu.stack=" + d.Project, "win.vampyuyu.server=" + id}})
	r, e := d.request(ctx, "GET", "/containers/json?all=true&filters="+url.QueryEscape(string(filter)))
	if e != nil {
		return "", e
	}
	defer r.Body.Close()
	var rows []struct {
		ID string `json:"Id"`
	}
	if e = json.NewDecoder(r.Body).Decode(&rows); e != nil {
		return "", e
	}
	if len(rows) != 1 {
		return "", errors.New("expected exactly one managed container")
	}
	return rows[0].ID, nil
}
func (d *Docker) Status(ctx context.Context, id string) (Status, error) {
	s := Status{ID: id, State: "unavailable", Commands: id != "velocity"}
	cid, e := d.container(ctx, id)
	if e != nil {
		return s, e
	}
	r, e := d.request(ctx, "GET", "/containers/"+cid+"/json")
	if e != nil {
		return s, e
	}
	defer r.Body.Close()
	var v struct {
		State struct {
			Status    string
			StartedAt string
			Health    struct{ Status string }
		}
	}
	if e = json.NewDecoder(r.Body).Decode(&v); e != nil {
		return s, e
	}
	s.State = v.State.Status
	s.StartedAt = v.State.StartedAt
	s.Health = v.State.Health.Status
	return s, nil
}
func (d *Docker) Action(ctx context.Context, id, action string) error {
	if action != "start" && action != "stop" && action != "restart" {
		return errors.New("invalid action")
	}
	cid, e := d.container(ctx, id)
	if e != nil {
		return e
	}
	r, e := d.request(ctx, "POST", "/containers/"+cid+"/"+action+"?t=60")
	if e != nil {
		return e
	}
	r.Body.Close()
	return nil
}
func (d *Docker) Logs(ctx context.Context, id string) ([]string, error) {
	cid, e := d.container(ctx, id)
	if e != nil {
		return nil, e
	}
	r, e := d.request(ctx, "GET", "/containers/"+cid+"/logs?stdout=true&stderr=true&timestamps=true&tail=200")
	if e != nil {
		return nil, e
	}
	defer r.Body.Close()
	// Docker non-TTY streams use an eight-byte multiplex header. Bound total data.
	reader := bufio.NewReader(io.LimitReader(r.Body, 2<<20))
	var out bytes.Buffer
	for {
		h, e := reader.Peek(8)
		if e == io.EOF {
			break
		}
		if e != nil {
			return nil, e
		}
		if h[0] > 2 || h[1] != 0 || h[2] != 0 || h[3] != 0 {
			return nil, errors.New("expected non-TTY Docker logs")
		}
		n := binary.BigEndian.Uint32(h[4:])
		if n > 1<<20 {
			return nil, errors.New("log frame too large")
		}
		reader.Discard(8)
		if _, e = io.CopyN(&out, reader, int64(n)); e != nil {
			return nil, e
		}
	}
	lines := strings.Split(strings.TrimSuffix(out.String(), "\n"), "\n")
	if len(lines) > 200 {
		lines = lines[len(lines)-200:]
	}
	for i := range lines {
		if len(lines[i]) > 8192 {
			lines[i] = lines[i][:8192] + "…"
		}
	}
	return lines, nil
}

type Server struct {
	GameToken string
	GameHTTP  *http.Client
	DataRoot  string
	Docker    *Docker
	Token     string
	Passwords map[string]string
	locks     [4]sync.Mutex
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	s.metricsRoute(mux)
	s.pluginRoutes(mux)
	s.gameRoutes(mux)
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok")) })
	mux.HandleFunc("GET /servers", func(w http.ResponseWriter, r *http.Request) {
		out := []Status{}
		for _, id := range IDs {
			v, _ := s.Docker.Status(r.Context(), id)
			out = append(out, v)
		}
		JSON(w, 200, out)
	})
	mux.HandleFunc("GET /servers/{id}/logs", func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		if !Valid(id) {
			JSON(w, 404, map[string]string{"error": "unknown server"})
			return
		}
		v, e := s.Docker.Logs(r.Context(), id)
		if e != nil {
			JSON(w, 503, map[string]string{"error": e.Error()})
			return
		}
		for i := range v {
			v[i] = s.redact(v[i])
		}
		JSON(w, 200, v)
	})
	mux.HandleFunc("POST /servers/{id}/{action}", func(w http.ResponseWriter, r *http.Request) {
		id, action := r.PathValue("id"), r.PathValue("action")
		if !Valid(id) {
			JSON(w, 404, map[string]string{"error": "unknown server"})
			return
		}
		if action != "start" && action != "stop" && action != "restart" && action != "command" {
			JSON(w, 400, map[string]string{"error": "invalid action"})
			return
		}
		var lock *sync.Mutex
		for i, v := range IDs {
			if v == id {
				lock = &s.locks[i]
			}
		}
		lock.Lock()
		defer lock.Unlock()
		if action == "command" {
			if id == "velocity" {
				JSON(w, 400, map[string]string{"error": "proxy commands are not supported"})
				return
			}
			var in struct {
				Command string `json:"command"`
			}
			if json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&in) != nil || strings.TrimSpace(in.Command) == "" || strings.ContainsAny(in.Command, "\r\n\x00") {
				JSON(w, 400, map[string]string{"error": "invalid command"})
				return
			}
			result, e := RCON(r.Context(), id+":25575", s.Passwords[id], strings.TrimPrefix(in.Command, "/"))
			if e != nil {
				slog.Warn("RCON request failed", "server", id, "error", e)
				JSON(w, 503, map[string]string{"error": "RCON unavailable or authentication failed"})
				return
			}
			JSON(w, 200, map[string]string{"output": s.redact(result)})
			return
		}
		if e := s.Docker.Action(r.Context(), id, action); e != nil {
			JSON(w, 503, map[string]string{"error": e.Error()})
			return
		}
		JSON(w, 200, map[string]bool{"ok": true})
	})
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !Equal(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "), s.Token) {
			JSON(w, 401, map[string]string{"error": "unauthorized"})
			return
		}
		mux.ServeHTTP(w, r)
	})
}
func (s *Server) redact(v string) string {
	for _, p := range s.Passwords {
		if p != "" {
			v = strings.ReplaceAll(v, p, "[redacted]")
		}
	}
	return strings.ReplaceAll(v, s.Token, "[redacted]")
}
func JSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

// RCON reads all output packets by following the command with a sentinel request.
func RCON(ctx context.Context, addr, password, command string) (string, error) {
	if len(password) < 32 {
		return "", errors.New("missing password")
	}
	c, e := (&net.Dialer{Timeout: 5 * time.Second}).DialContext(ctx, "tcp", addr)
	if e != nil {
		return "", e
	}
	defer c.Close()
	deadline := time.Now().Add(8 * time.Second)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	c.SetDeadline(deadline)
	write := func(id, kind int32, payload string) error {
		b := make([]byte, 14+len(payload))
		binary.LittleEndian.PutUint32(b, uint32(len(b)-4))
		binary.LittleEndian.PutUint32(b[4:], uint32(id))
		binary.LittleEndian.PutUint32(b[8:], uint32(kind))
		copy(b[12:], payload)
		_, err := c.Write(b)
		return err
	}
	read := func() (int32, int32, string, error) {
		var header [4]byte
		if _, e := io.ReadFull(c, header[:]); e != nil {
			return 0, 0, "", e
		}
		n := binary.LittleEndian.Uint32(header[:])
		if n < 10 || n > 1<<20 {
			return 0, 0, "", errors.New("invalid RCON frame")
		}
		b := make([]byte, n)
		if _, e := io.ReadFull(c, b); e != nil {
			return 0, 0, "", e
		}
		return int32(binary.LittleEndian.Uint32(b)), int32(binary.LittleEndian.Uint32(b[4:])), string(b[8 : len(b)-2]), nil
	}
	if e = write(1, 3, password); e != nil {
		return "", e
	}
	id, kind, _, e := read()
	if e == nil && kind == 0 {
		id, kind, _, e = read()
	}
	if e != nil || id != 1 || kind != 2 {
		return "", errors.New("RCON authentication failed")
	}
	if e = write(2, 2, command); e != nil {
		return "", e
	}
	// Wait for the first reply before the sentinel. Some Minecraft RCON
	// servers reject multiple coalesced request frames in a single socket read.
	id, _, first, e := read()
	if e != nil {
		return "", e
	}
	if id != 2 {
		return "", errors.New("unexpected RCON response")
	}
	if e = write(3, 2, ""); e != nil {
		return "", e
	}
	var out strings.Builder
	out.WriteString(first)
	for out.Len() < 1<<20 {
		id, _, v, e := read()
		if e != nil {
			return "", e
		}
		if id == 3 {
			return out.String(), nil
		}
		if id != 2 {
			return "", errors.New("unexpected RCON response")
		}
		out.WriteString(v)
	}
	return "", errors.New("RCON response too large")
}
