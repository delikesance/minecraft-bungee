package panel

import (
	"bytes"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func setup(t *testing.T) (*Server, string) {
	t.Helper()
	p := filepath.Join(t.TempDir(), "panel.db")
	db, e := Open(p)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { db.Close() })
	if e = Bootstrap(db, "admin", "a-long-password-for-tests"); e != nil {
		t.Fatal(e)
	}
	return New(db, "https://panel.test", "http://127.0.0.1:1", strings.Repeat("x", 64)), p
}
func request(t *testing.T, s *Server, method, path, body string, cookie *http.Cookie, csrf string) *httptest.ResponseRecorder {
	t.Helper()
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.RemoteAddr = "127.0.0.1:1234"
	r.Header.Set("Origin", s.Origin)
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("X-CSRF-Token", csrf)
	if cookie != nil {
		r.AddCookie(cookie)
	}
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	return w
}
func login(t *testing.T, s *Server) (*http.Cookie, string) {
	t.Helper()
	w := request(t, s, "POST", "/api/v1/login", `{"username":"admin","password":"a-long-password-for-tests"}`, nil, "")
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	var v map[string]string
	json.Unmarshal(w.Body.Bytes(), &v)
	c := w.Result().Cookies()[0]
	if !c.Secure || !c.HttpOnly || c.SameSite != http.SameSiteStrictMode {
		t.Fatal("weak cookie")
	}
	return c, v["csrf"]
}
func TestSessionsCSRFAndPersistence(t *testing.T) {
	s, p := setup(t)
	if w := request(t, s, "GET", "/api/v1/servers", "", nil, ""); w.Code != 401 {
		t.Fatal(w.Code)
	}
	c, csrf := login(t, s)
	if w := request(t, s, "POST", "/api/v1/logout", "", c, ""); w.Code != 403 {
		t.Fatal(w.Code)
	}
	db, e := Open(p)
	if e != nil {
		t.Fatal(e)
	}
	defer db.Close()
	restarted := New(db, s.Origin, s.Controller, s.Token)
	if w := request(t, restarted, "GET", "/api/v1/session", "", c, ""); w.Code != 200 {
		t.Fatal(w.Code)
	}
	if w := request(t, s, "POST", "/api/v1/logout", "", c, csrf); w.Code != 200 {
		t.Fatal(w.Code)
	}
	if w := request(t, restarted, "GET", "/api/v1/session", "", c, ""); w.Code != 401 {
		t.Fatal(w.Code)
	}
}
func TestOriginAndThrottle(t *testing.T) {
	s, _ := setup(t)
	r := httptest.NewRequest("POST", "/api/v1/login", bytes.NewBufferString(`{}`))
	r.Header.Set("Origin", "https://evil.test")
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if w.Code != 403 {
		t.Fatal(w.Code)
	}
	for i := 0; i < 8; i++ {
		w = request(t, s, "POST", "/api/v1/login", `{"username":"admin","password":"wrong"}`, nil, "")
		if w.Code != 401 {
			t.Fatal(w.Code)
		}
	}
	if w = request(t, s, "POST", "/api/v1/login", `{}`, nil, ""); w.Code != 429 {
		t.Fatal(w.Code)
	}
}
func TestAuditAndUnavailableController(t *testing.T) {
	s, _ := setup(t)
	c, csrf := login(t, s)
	w := request(t, s, "POST", "/api/v1/servers/creative/start", "", c, csrf)
	if w.Code != 503 {
		t.Fatal(w.Code)
	}
	var action string
	var status int
	if e := s.DB.QueryRow("SELECT action,result FROM audit").Scan(&action, &status); e != nil || action != "start" || status != 503 {
		t.Fatal(action, status, e)
	}
	if w = request(t, s, "POST", "/api/v1/servers/velocity/command", `{"command":"list"}`, c, csrf); w.Code != 400 {
		t.Fatal(w.Code)
	}
}
func TestExpiredSessionAndBootstrap(t *testing.T) {
	s, _ := setup(t)
	c, _ := login(t, s)
	s.DB.Exec("UPDATE sessions SET expires=0")
	if w := request(t, s, "GET", "/api/v1/session", "", c, ""); w.Code != 401 {
		t.Fatal(w.Code)
	}
	if e := Bootstrap(s.DB, "other", "a-long-password-for-tests"); e == nil {
		t.Fatal("second admin allowed")
	}
}

func TestPlayerRoleAPIRequiresAuthenticationAndCSRF(t *testing.T) {
	s, _ := setup(t)
	id := "12345678-1234-4234-8234-123456789abc"
	if _, err := s.DB.Exec("INSERT INTO players(uuid,name,first_seen,last_seen) VALUES(?,?,?,?)", id, "Alice", 1, 1); err != nil {
		t.Fatal(err)
	}
	if w := request(t, s, "GET", "/api/v1/players", "", nil, ""); w.Code != 401 {
		t.Fatal(w.Code)
	}
	cookie, csrf := login(t, s)
	if w := request(t, s, "PUT", "/api/v1/players/"+id+"/role", `{"role":"owner"}`, cookie, ""); w.Code != 403 {
		t.Fatal(w.Code)
	}
	if w := request(t, s, "PUT", "/api/v1/players/"+id+"/role", `{"role":"op"}`, cookie, csrf); w.Code != 400 {
		t.Fatal(w.Code)
	}
	if w := request(t, s, "PUT", "/api/v1/players/"+id+"/role", `{"role":"owner"}`, cookie, csrf); w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	w := request(t, s, "GET", "/api/v1/players?role=owner", "", cookie, "")
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"role":"owner"`) {
		t.Fatal(w.Code, w.Body.String())
	}
	if w := request(t, s, "GET", "/api/v1/players?page=0", "", cookie, ""); w.Code != 400 {
		t.Fatal(w.Code)
	}
	if w := request(t, s, "GET", "/api/v1/roles", "", cookie, ""); w.Code != 200 || !strings.Contains(w.Body.String(), "Yuyu's Bat") {
		t.Fatal(w.Code, w.Body.String())
	}
}

func TestManagementRequiresSessionAndCSRF(t *testing.T) {
	s, _ := setup(t)
	for _, route := range []string{"/api/v1/role-commands", "/api/v1/servers/creative/metrics", "/api/v1/servers/creative/plugins"} {
		if w := request(t, s, "GET", route, "", nil, ""); w.Code != 401 {
			t.Fatal(route, w.Code)
		}
	}
	cookie, csrf := login(t, s)
	for _, test := range []struct{ method, path string }{{"PUT", "/api/v1/roles/mod/commands"}, {"POST", "/api/v1/servers/creative/plugins/Test.jar"}, {"DELETE", "/api/v1/servers/creative/plugins/Test.jar"}} {
		if w := request(t, s, test.method, test.path, `{"commands":["kick"]}`, cookie, ""); w.Code != 403 {
			t.Fatal(test.path, w.Code)
		}
	}
	if w := request(t, s, "POST", "/api/v1/players/12345678-1234-4234-8234-123456789abc/operator", `{"server":"creative","enabled":true}`, cookie, ""); w.Code != 403 {
		t.Fatal(w.Code)
	}
	if w := request(t, s, "PUT", "/api/v1/roles/mod/commands", `{"commands":["op"]}`, cookie, csrf); w.Code != 400 {
		t.Fatal(w.Code)
	}
	if w := request(t, s, "PUT", "/api/v1/roles/mod/commands", `{"commands":["kick","fly"]}`, cookie, csrf); w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	if w := request(t, s, "GET", "/api/v1/role-commands", "", cookie, ""); w.Code != 200 || !strings.Contains(w.Body.String(), `"mod":["fly","kick"]`) {
		t.Fatal(w.Code, w.Body.String())
	}
}

func TestInventoryManagementAuthValidationAndAudit(t *testing.T) {
	s, _ := setup(t)
	for _, route := range []string{"/api/v1/servers/creative/game/arenas", "/api/v1/activity", "/api/v1/servers/creative/game/state", "/api/v1/servers/creative/game/materials", "/api/v1/servers/creative/game/players/12345678-1234-4234-8234-123456789abc/inventory"} {
		if w := request(t, s, "GET", route, "", nil, ""); w.Code != 401 {
			t.Fatal(route, w.Code)
		}
	}
	cookie, csrf := login(t, s)
	if w := request(t, s, "POST", "/api/v1/servers/creative/game/action", `{"action":"gamemode","uuid":"12345678-1234-4234-8234-123456789abc","value":"CREATIVE"}`, cookie, ""); w.Code != 403 {
		t.Fatal(w.Code)
	}
	if w := request(t, s, "POST", "/api/v1/servers/creative/game/action", `{"action":"exec","command":"op Alice"}`, cookie, csrf); w.Code != 400 {
		t.Fatal(w.Code)
	}
	if w := request(t, s, "GET", "/api/v1/servers/creative/game/players/../../inventory", "", cookie, csrf); w.Code == 200 {
		t.Fatal("unsafe inventory path accepted")
	}
	if w := request(t, s, "GET", "/api/v1/activity?before=bad", "", cookie, ""); w.Code != 400 {
		t.Fatal(w.Code)
	}
	if w := request(t, s, "GET", "/api/v1/activity", "", cookie, ""); w.Code != 200 {
		t.Fatal(w.Code)
	}
}

func TestArenaActionsRequireCSRFAndFixedOperation(t *testing.T) {
	s, _ := setup(t)
	cookie, csrf := login(t, s)
	for _, action := range []string{"arena-save", "arena-start", "arena-ban", "arena-announce"} {
		body := `{"action":"` + action + `","arenaId":"werewolf"}`
		if w := request(t, s, "POST", "/api/v1/servers/creative/game/action", body, cookie, ""); w.Code != 403 {
			t.Fatal(action, w.Code)
		}
	}
	for _, action := range []string{"arena-exec", "arena-op", "arena-command"} {
		if w := request(t, s, "POST", "/api/v1/servers/creative/game/action", `{"action":"`+action+`"}`, cookie, csrf); w.Code != 400 {
			t.Fatal(action, w.Code)
		}
	}
}

func TestMenuPackServedOnlyByHash(t *testing.T) {
	s, _ := setup(t)
	file := filepath.Join(t.TempDir(), "pack.zip")
	if e := os.WriteFile(file, []byte("PK-test-pack"), 0o600); e != nil {
		t.Fatal(e)
	}
	s.PackFile = file
	sum := sha1.Sum([]byte("PK-test-pack"))
	digest := hex.EncodeToString(sum[:])
	get := func(path string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("GET", path, nil)
		w := httptest.NewRecorder()
		s.Handler().ServeHTTP(w, r)
		return w
	}
	ok := get("/pack/" + digest + ".zip")
	if ok.Code != 200 || ok.Body.String() != "PK-test-pack" || ok.Header().Get("Content-Type") != "application/zip" {
		t.Fatalf("pack not served: %d %q %q", ok.Code, ok.Body.String(), ok.Header().Get("Content-Type"))
	}
	if !strings.Contains(ok.Header().Get("Cache-Control"), "immutable") {
		t.Fatal("pack must be cacheable by hash")
	}
	for _, path := range []string{"/pack/" + strings.Repeat("0", 40) + ".zip", "/pack/other.zip"} {
		if w := get(path); w.Code != 404 || strings.Contains(w.Body.String(), "<html") {
			t.Fatalf("%s must be a plain 404, got %d", path, w.Code)
		}
	}
	s2, _ := setup(t)
	if w := httptest.NewRecorder(); true {
		s2.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/pack/"+digest+".zip", nil))
		if w.Code != 404 {
			t.Fatal("no pack configured must be 404")
		}
	}
}
