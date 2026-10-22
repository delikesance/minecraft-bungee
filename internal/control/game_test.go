package control

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestGameBoundaryAndInventoryConflict(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+strings.Repeat("g", 64) {
			t.Error("wrong bridge credential")
		}
		if r.URL.Path != "/players/12345678-1234-4234-8234-123456789abc/inventory" {
			t.Error("unexpected route", r.URL.Path)
		}
		if r.Method == "PUT" {
			w.WriteHeader(409)
			io.WriteString(w, `{"error":"Inventory changed. Refresh before editing."}`)
		} else {
			io.WriteString(w, `{"slots":[],"revision":"safe"}`)
		}
	}))
	defer upstream.Close()
	calls := 0
	docker := fakeDocker(t, func(w http.ResponseWriter, r *http.Request) {
		calls++
		if strings.Contains(r.URL.Path, "/containers/json") {
			io.WriteString(w, `[{"Id":"managed"}]`)
		} else {
			io.WriteString(w, `{"NetworkSettings":{"Networks":{"game":{"IPAddress":"127.0.0.1"}}}}`)
		}
	})
	s := &Server{Docker: docker, Token: strings.Repeat("c", 64), GameToken: strings.Repeat("g", 64), GameHTTP: &http.Client{Transport: rewriteTransport{base: upstream.URL}}}
	route := "/servers/creative/game/players/12345678-1234-4234-8234-123456789abc/inventory"
	for _, method := range []string{"GET", "PUT"} {
		w := controllerRequest(s, method, route, []byte(`{"revision":"old"}`))
		expected := 200
		if method == "PUT" {
			expected = 409
		}
		if w.Code != expected {
			t.Fatal(w.Code, w.Body.String())
		}
	}
	before := calls
	for _, action := range []string{"op", "gamemode;op Alice", "command", "exec", "stop", "*"} {
		w := controllerRequest(s, "POST", "/servers/creative/game/action", []byte(`{"action":"`+action+`"}`))
		if w.Code != 400 {
			t.Fatal(action, w.Code)
		}
	}
	if calls != before {
		t.Fatal("invalid action reached Docker")
	}
	w := controllerRequest(s, "GET", "/servers/unmanaged/game/state", nil)
	if w.Code != 503 {
		t.Fatal(w.Code)
	}
	if calls != before {
		t.Fatal("unmanaged bridge queried Docker")
	}
}

func TestArenaProxyUsesPrivatePortAndFixedPath(t *testing.T) {
	var seen []string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = append(seen, r.Method+" "+r.URL.Path)
		io.WriteString(w, `{"ok":true}`)
	}))
	defer upstream.Close()
	docker := fakeDocker(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "/containers/json") {
			io.WriteString(w, `[{"Id":"managed"}]`)
		} else {
			io.WriteString(w, `{"NetworkSettings":{"Networks":{"game":{"IPAddress":"127.0.0.1"}}}}`)
		}
	})
	transport := arenaTransport{t: t, base: upstream.URL}
	s := &Server{Docker: docker, Token: strings.Repeat("c", 64), GameToken: strings.Repeat("g", 64), GameHTTP: &http.Client{Transport: transport}}
	if w := controllerRequest(s, "GET", "/servers/creative/game/arenas", nil); w.Code != 200 {
		t.Fatal(w.Code)
	}
	if w := controllerRequest(s, "POST", "/servers/creative/game/action", []byte(`{"action":"arena-start","arenaId":"village"}`)); w.Code != 200 {
		t.Fatal(w.Code)
	}
	if len(seen) != 2 || seen[0] != "GET /arenas" || seen[1] != "POST /arenas/action" {
		t.Fatal(seen)
	}
	if w := controllerRequest(s, "POST", "/servers/creative/game/action", []byte(`{"action":"arena-exec"}`)); w.Code != 400 {
		t.Fatal(w.Code)
	}
}

type arenaTransport struct {
	t    *testing.T
	base string
}

func (a arenaTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	if !strings.HasSuffix(r.URL.Host, ":8085") {
		a.t.Fatal("wrong arena port", r.URL.Host)
	}
	return (rewriteTransport{base: a.base}).RoundTrip(r)
}

func TestGameLogsAreProxiedAsPlainTextDownloads(t *testing.T) {
	var seen []string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = append(seen, r.URL.Path)
		if r.URL.Path == "/arenas/logs" {
			io.WriteString(w, `{"logs":[]}`)
			return
		}
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.Header().Set("Content-Disposition", `attachment; filename="game.log"`)
		io.WriteString(w, "12:00:00.000 +00:00.0 [abcd1234] [Night 1] DEAL     8 players\n")
	}))
	defer upstream.Close()
	docker := fakeDocker(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "/containers/json") {
			io.WriteString(w, `[{"Id":"managed"}]`)
		} else {
			io.WriteString(w, `{"NetworkSettings":{"Networks":{"game":{"IPAddress":"127.0.0.1"}}}}`)
		}
	})
	s := &Server{Docker: docker, Token: strings.Repeat("c", 64), GameToken: strings.Repeat("g", 64), GameHTTP: &http.Client{Transport: arenaTransport{t: t, base: upstream.URL}}}
	if w := controllerRequest(s, "GET", "/servers/creative/game/logs", nil); w.Code != 200 || w.Header().Get("Content-Type") != "application/json" {
		t.Fatal(w.Code, w.Header())
	}
	w := controllerRequest(s, "GET", "/servers/creative/game/logs/2026-10-22_18-55-01_village_abcd1234.log", nil)
	if w.Code != 200 || w.Header().Get("Content-Type") != "text/plain; charset=utf-8" || !strings.Contains(w.Body.String(), "DEAL") || !strings.Contains(w.Header().Get("Content-Disposition"), "game.log") {
		t.Fatal(w.Code, w.Header(), w.Body.String())
	}
	for _, bad := range []string{"..%2Fsecrets.log", "game.txt", "a%20b.log"} {
		if w := controllerRequest(s, "GET", "/servers/creative/game/logs/"+bad, nil); w.Code == 200 {
			t.Fatal(bad, w.Code)
		}
	}
	if len(seen) != 2 || seen[0] != "/arenas/logs" || seen[1] != "/arenas/logs/2026-10-22_18-55-01_village_abcd1234.log" {
		t.Fatal(seen)
	}
}
