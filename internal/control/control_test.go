package control

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func fakeDocker(t *testing.T, fn http.HandlerFunc) *Docker {
	t.Helper()
	srv := httptest.NewServer(fn)
	t.Cleanup(srv.Close)
	return &Docker{Project: "test", HTTP: &http.Client{Transport: rewriteTransport{base: srv.URL}}}
}

type rewriteTransport struct{ base string }

func (r rewriteTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	v := req.Clone(req.Context())
	u := *v.URL
	v.URL = &u
	target, _ := http.NewRequest("GET", r.base, nil)
	v.URL.Scheme = target.URL.Scheme
	v.URL.Host = target.URL.Host
	return http.DefaultTransport.RoundTrip(v)
}
func TestContainerLabelBoundary(t *testing.T) {
	calls := 0
	d := fakeDocker(t, func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.URL.Path == "/v1.44/containers/json" {
			var filters map[string][]string
			if e := json.Unmarshal([]byte(r.URL.Query().Get("filters")), &filters); e != nil {
				t.Error(e)
			}
			if strings.Join(filters["label"], ",") != "win.vampyuyu.stack=test,win.vampyuyu.server=creative" {
				t.Error(filters)
			}
			io.WriteString(w, `[{"Id":"managed"}]`)
			return
		}
		if r.URL.Path != "/v1.44/containers/managed/stop" || r.URL.Query().Get("t") != "60" || r.Method != "POST" {
			t.Error(r.URL, r.Method)
		}
		w.WriteHeader(204)
	})
	if e := d.Action(context.Background(), "creative", "stop"); e != nil {
		t.Fatal(e)
	}
	if calls != 2 {
		t.Fatal(calls)
	}
	if e := d.Action(context.Background(), "other", "stop"); e == nil {
		t.Fatal("unmanaged container accepted")
	}
	if e := d.Action(context.Background(), "creative", "exec"); e == nil {
		t.Fatal("arbitrary operation accepted")
	}
	if calls != 2 {
		t.Fatal("invalid input reached Docker")
	}
}
func TestAmbiguousContainerRejected(t *testing.T) {
	d := fakeDocker(t, func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, `[{"Id":"a"},{"Id":"b"}]`) })
	if _, e := d.container(context.Background(), "lobby"); e == nil {
		t.Fatal("ambiguous container accepted")
	}
}
func TestControllerAuthAndRedaction(t *testing.T) {
	s := Server{Token: strings.Repeat("t", 64), Passwords: map[string]string{"lobby": strings.Repeat("p", 64)}}
	r := httptest.NewRequest("POST", "/servers/creative/exec", nil)
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatal(w.Code)
	}
	r.Header.Set("Authorization", "Bearer "+s.Token)
	w = httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if w.Code != 400 {
		t.Fatal(w.Code)
	}
	if got := s.redact("password=" + s.Passwords["lobby"] + " token=" + s.Token); strings.Contains(got, s.Token) || strings.Contains(got, s.Passwords["lobby"]) {
		t.Fatal("secret leaked")
	}
}
func TestMultiplexedLogBounds(t *testing.T) {
	d := fakeDocker(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "/containers/json") {
			io.WriteString(w, `[{"Id":"a"}]`)
			return
		}
		payload := strings.Repeat("line\n", 300)
		h := make([]byte, 8)
		h[0] = 1
		binary.BigEndian.PutUint32(h[4:], uint32(len(payload)))
		w.Write(h)
		io.WriteString(w, payload)
	})
	lines, e := d.Logs(context.Background(), "lobby")
	if e != nil || len(lines) != 200 {
		t.Fatal(len(lines), e)
	}
}
func TestRCONCollectsPackets(t *testing.T) {
	listener, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer listener.Close()
	go func() {
		c, e := listener.Accept()
		if e != nil {
			return
		}
		defer c.Close()
		read := func() {
			var h [4]byte
			io.ReadFull(c, h[:])
			io.CopyN(io.Discard, c, int64(binary.LittleEndian.Uint32(h[:])))
		}
		write := func(id uint32, v string) {
			b := make([]byte, len(v)+14)
			binary.LittleEndian.PutUint32(b, uint32(len(b)-4))
			binary.LittleEndian.PutUint32(b[4:], id)
			if id == 1 {
				binary.LittleEndian.PutUint32(b[8:], 2)
			}
			copy(b[12:], v)
			c.Write(b)
		}
		read()
		write(1, "")
		read()
		write(2, "first")
		write(2, "second")
		read()
		write(3, "")
	}()
	v, e := RCON(context.Background(), listener.Addr().String(), strings.Repeat("s", 64), "list")
	if e != nil || v != "firstsecond" {
		t.Fatal(v, e)
	}
}
