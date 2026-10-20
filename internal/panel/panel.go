package panel

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"embed"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"net"
	"net/http"
	"os"
	"path"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/garder500/minecraft-bungee/internal/control"
	"github.com/garder500/minecraft-bungee/internal/players"
	"golang.org/x/crypto/bcrypt"
	_ "modernc.org/sqlite"
)

//go:embed static/*
var Assets embed.FS

type attempt struct {
	Count int
	Until time.Time
}
type Server struct {
	DB                        *sql.DB
	Origin, Controller, Token string
	Secure                    bool
	HTTP                      *http.Client
	// PackFile is the menu resource pack served publicly at /pack/<sha1>.zip.
	PackFile string
	pack     packCache
	mu       sync.Mutex
	attempts map[string]attempt
	streams  chan struct{}
}

func Open(filename string) (*sql.DB, error) {
	db, e := sql.Open("sqlite", filename)
	if e != nil {
		return nil, e
	}
	db.SetMaxOpenConns(1)
	_, e = db.Exec(`PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;
 CREATE TABLE IF NOT EXISTS admins(username TEXT PRIMARY KEY,password BLOB NOT NULL);
 CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY,username TEXT NOT NULL,csrf TEXT NOT NULL,expires INTEGER NOT NULL);
 CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY,at TEXT NOT NULL,username TEXT NOT NULL,server TEXT NOT NULL,action TEXT NOT NULL,result INTEGER NOT NULL);`)
	if e != nil {
		db.Close()
		return nil, e
	}
	if e = players.Migrate(db); e != nil {
		db.Close()
		return nil, e
	}
	return db, nil
}
func Bootstrap(db *sql.DB, username, password string) error {
	if len(password) < 14 || len(password) > 72 {
		return fmt.Errorf("password must be 14–72 bytes")
	}
	if len(username) < 1 || len(username) > 64 || strings.ContainsAny(username, "\r\n\x00") {
		return fmt.Errorf("invalid username")
	}
	h, e := bcrypt.GenerateFromPassword([]byte(password), 12)
	if e != nil {
		return e
	}
	tx, e := db.Begin()
	if e != nil {
		return e
	}
	defer tx.Rollback()
	var n int
	if e = tx.QueryRow("SELECT COUNT(*) FROM admins").Scan(&n); e != nil {
		return e
	}
	if n > 0 {
		return fmt.Errorf("administrator already exists")
	}
	if _, e = tx.Exec("INSERT INTO admins VALUES(?,?)", username, h); e != nil {
		return e
	}
	return tx.Commit()
}
func New(db *sql.DB, origin, controller, token string) *Server {
	return &Server{DB: db, Origin: origin, Controller: controller, Token: token, Secure: strings.HasPrefix(origin, "https://"), HTTP: &http.Client{Timeout: 70 * time.Second}, attempts: map[string]attempt{}, streams: make(chan struct{}, 32)}
}
func random() string {
	b := make([]byte, 32)
	if _, e := rand.Read(b); e != nil {
		panic(e)
	}
	return hex.EncodeToString(b)
}
func hash(v string) string { h := sha256.Sum256([]byte(v)); return hex.EncodeToString(h[:]) }

type session struct{ User, CSRF string }

func (s *Server) session(r *http.Request) (session, bool) {
	c, e := r.Cookie("mc_session")
	if e != nil || len(c.Value) != 64 {
		return session{}, false
	}
	var v session
	e = s.DB.QueryRow("SELECT username,csrf FROM sessions WHERE token=? AND expires>?", hash(c.Value), time.Now().Unix()).Scan(&v.User, &v.CSRF)
	return v, e == nil
}
func (s *Server) cookie(w http.ResponseWriter, value string, age int) {
	http.SetCookie(w, &http.Cookie{Name: "mc_session", Value: value, Path: "/", HttpOnly: true, Secure: s.Secure, SameSite: http.SameSiteStrictMode, MaxAge: age})
}
func fail(w http.ResponseWriter, status int, msg string) {
	control.JSON(w, status, map[string]string{"error": msg})
}
func (s *Server) limited(key string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	for k, v := range s.attempts {
		if now.After(v.Until) {
			delete(s.attempts, k)
		}
	}
	v := s.attempts[key]
	if v.Count >= 8 || len(s.attempts) > 10000 {
		return true
	}
	if v.Count == 0 {
		v.Until = now.Add(5 * time.Minute)
	}
	v.Count++
	s.attempts[key] = v
	return false
}
func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	return decodeSized(w, r, v, 4096)
}
func decodeSized(w http.ResponseWriter, r *http.Request, v any, limit int64) bool {
	if !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
		fail(w, 415, "JSON required")
		return false
	}
	d := json.NewDecoder(http.MaxBytesReader(w, r.Body, limit))
	d.DisallowUnknownFields()
	if d.Decode(v) != nil {
		fail(w, 400, "invalid JSON")
		return false
	}
	var extra any
	if d.Decode(&extra) != io.EOF {
		fail(w, 400, "invalid JSON")
		return false
	}
	return true
}
func (s *Server) upstream(ctx context.Context, method, endpoint string, body io.Reader) (*http.Response, error) {
	req, e := http.NewRequestWithContext(ctx, method, s.Controller+endpoint, body)
	if e != nil {
		return nil, e
	}
	req.Header.Set("Authorization", "Bearer "+s.Token)
	req.Header.Set("Content-Type", "application/json")
	return s.HTTP.Do(req)
}
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		if s.DB.PingContext(r.Context()) != nil {
			fail(w, 503, "database unavailable")
			return
		}
		w.Write([]byte("ok"))
	})
	mux.HandleFunc("POST /api/v1/login", func(w http.ResponseWriter, r *http.Request) {
		host, _, _ := net.SplitHostPort(r.RemoteAddr)
		if s.limited(host) {
			fail(w, 429, "too many login attempts; retry in five minutes")
			return
		}
		var in struct {
			Username string `json:"username"`
			Password string `json:"password"`
		}
		if !decode(w, r, &in) {
			return
		}
		var h []byte
		e := s.DB.QueryRow("SELECT password FROM admins WHERE username=?", in.Username).Scan(&h)
		if e != nil {
			h = []byte("$2a$12$Gm.7dEHAHYTQtz5cCKtXwuF6.2bOzcmkzj2T4u/FnYx4t2JC1/7zS")
		}
		valid := bcrypt.CompareHashAndPassword(h, []byte(in.Password))
		if e != nil || valid != nil {
			fail(w, 401, "invalid credentials")
			return
		}
		token, csrf := random(), random()
		tx, e := s.DB.Begin()
		if e != nil {
			fail(w, 500, "session unavailable")
			return
		}
		defer tx.Rollback()
		_, e = tx.Exec("DELETE FROM sessions WHERE expires<=?", time.Now().Unix())
		if e == nil {
			_, e = tx.Exec("INSERT INTO sessions VALUES(?,?,?,?)", hash(token), in.Username, csrf, time.Now().Add(24*time.Hour).Unix())
		}
		if e == nil {
			e = tx.Commit()
		}
		if e != nil {
			fail(w, 500, "session unavailable")
			return
		}
		s.cookie(w, token, 86400)
		control.JSON(w, 200, map[string]string{"username": in.Username, "csrf": csrf})
	})
	protected := func(fn func(http.ResponseWriter, *http.Request, session)) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			v, ok := s.session(r)
			if !ok {
				fail(w, 401, "login required")
				return
			}
			if r.Method != "GET" && !control.Equal(r.Header.Get("X-CSRF-Token"), v.CSRF) {
				fail(w, 403, "invalid CSRF token")
				return
			}
			fn(w, r, v)
		}
	}
	s.managementRoutes(mux, protected)
	s.gameRoutes(mux, protected)
	directory := &players.Service{DB: s.DB}
	mux.HandleFunc("GET /api/v1/roles", protected(func(w http.ResponseWriter, r *http.Request, v session) { control.JSON(w, 200, players.Catalog) }))
	mux.HandleFunc("GET /api/v1/players", protected(func(w http.ResponseWriter, r *http.Request, v session) {
		page := 1
		var err error
		if raw := r.URL.Query().Get("page"); raw != "" {
			page, err = strconv.Atoi(raw)
		}
		search, role := strings.TrimSpace(r.URL.Query().Get("search")), r.URL.Query().Get("role")
		if err != nil || page < 1 || page > 100000 || len(search) > 64 || (role != "" && !players.ValidRole(role)) {
			fail(w, 400, "invalid player filter")
			return
		}
		result, err := directory.List(search, role, page)
		if err != nil {
			fail(w, 500, "player directory unavailable")
			return
		}
		control.JSON(w, 200, result)
	}))
	mux.HandleFunc("PUT /api/v1/players/{uuid}/role", protected(func(w http.ResponseWriter, r *http.Request, v session) {
		id := r.PathValue("uuid")
		if !players.ValidUUID(id) {
			fail(w, 400, "invalid player UUID")
			return
		}
		var input struct {
			Role string `json:"role"`
		}
		if !decode(w, r, &input) {
			return
		}
		if !players.ValidRole(input.Role) {
			fail(w, 400, "unknown cosmetic role")
			return
		}
		found, err := directory.SetRole(id, input.Role, v.User)
		if err != nil {
			fail(w, 500, "role update failed")
			return
		}
		if !found {
			fail(w, 404, "player not found")
			return
		}
		control.JSON(w, 200, map[string]any{"uuid": id, "role": input.Role, "ok": true})
	}))
	mux.HandleFunc("GET /api/v1/session", protected(func(w http.ResponseWriter, r *http.Request, v session) {
		control.JSON(w, 200, map[string]string{"username": v.User, "csrf": v.CSRF})
	}))
	mux.HandleFunc("POST /api/v1/logout", protected(func(w http.ResponseWriter, r *http.Request, v session) {
		c, _ := r.Cookie("mc_session")
		if _, e := s.DB.Exec("DELETE FROM sessions WHERE token=?", hash(c.Value)); e != nil {
			fail(w, 500, "logout failed")
			return
		}
		s.cookie(w, "", -1)
		control.JSON(w, 200, map[string]bool{"ok": true})
	}))
	mux.HandleFunc("GET /api/v1/servers", protected(func(w http.ResponseWriter, r *http.Request, v session) { s.relay(w, r, "GET", "/servers", nil) }))
	mux.HandleFunc("POST /api/v1/servers/{id}/{action}", protected(func(w http.ResponseWriter, r *http.Request, v session) {
		id, action := r.PathValue("id"), r.PathValue("action")
		if !control.Valid(id) {
			fail(w, 404, "unknown server")
			return
		}
		if action != "start" && action != "stop" && action != "restart" && action != "command" {
			fail(w, 400, "invalid action")
			return
		}
		var body io.Reader
		if action == "command" {
			if id == "velocity" {
				fail(w, 400, "proxy commands are not supported")
				return
			}
			var in struct {
				Command string `json:"command"`
			}
			if !decode(w, r, &in) {
				return
			}
			if strings.TrimSpace(in.Command) == "" || strings.ContainsAny(in.Command, "\r\n\x00") {
				fail(w, 400, "invalid command")
				return
			}
			b, _ := json.Marshal(in)
			body = strings.NewReader(string(b))
		}
		// Persist intent before sending a privileged operation. Never store command text.
		result, e := s.DB.Exec("INSERT INTO audit(at,username,server,action,result) VALUES(?,?,?,?,?)", time.Now().UTC().Format(time.RFC3339), v.User, id, action, 0)
		if e != nil {
			fail(w, 500, "audit unavailable")
			return
		}
		auditID, _ := result.LastInsertId()
		status := s.relay(w, r, "POST", "/servers/"+id+"/"+action, body)
		if _, e = s.DB.Exec("UPDATE audit SET result=? WHERE id=?", status, auditID); e != nil {
			slog.Error("audit result update failed", "audit_id", auditID)
		}
		slog.Info("admin action", "actor", v.User, "server", id, "action", action, "status", status)
	}))
	mux.HandleFunc("GET /api/v1/servers/{id}/console", protected(s.console))
	mux.HandleFunc("GET /pack/{file}", s.servePack)
	mux.HandleFunc("/api/", func(w http.ResponseWriter, r *http.Request) { fail(w, 404, "unknown endpoint") })
	static, _ := fs.Sub(Assets, "static")
	files := http.FileServer(http.FS(static))
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		p := strings.TrimPrefix(path.Clean(r.URL.Path), "/")
		if p == "" {
			p = "index.html"
		}
		if _, e := fs.Stat(static, p); e != nil {
			if strings.HasPrefix(p, "assets/") {
				http.NotFound(w, r)
				return
			}
			http.ServeFileFS(w, r, static, "index.html")
			return
		}
		files.ServeHTTP(w, r)
	})
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "same-origin")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'")
		if strings.HasPrefix(r.URL.Path, "/api/") {
			w.Header().Set("Cache-Control", "no-store")
		}
		if o := r.Header.Get("Origin"); o != "" && o != s.Origin {
			fail(w, 403, "origin rejected")
			return
		}
		if r.Method != "GET" && r.Method != "HEAD" && r.Header.Get("Origin") != s.Origin {
			fail(w, 403, "origin required")
			return
		}
		mux.ServeHTTP(w, r)
	})
}
func (s *Server) relay(w http.ResponseWriter, r *http.Request, method, endpoint string, body io.Reader) int {
	res, e := s.upstream(r.Context(), method, endpoint, body)
	if e != nil {
		fail(w, 503, "controller unavailable")
		return 503
	}
	defer res.Body.Close()
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(res.StatusCode)
	io.Copy(w, io.LimitReader(res.Body, 2<<20))
	return res.StatusCode
}
func (s *Server) console(w http.ResponseWriter, r *http.Request, v session) {
	id := r.PathValue("id")
	if !control.Valid(id) {
		fail(w, 404, "unknown server")
		return
	}
	select {
	case s.streams <- struct{}{}:
		defer func() { <-s.streams }()
	default:
		fail(w, 429, "too many console streams")
		return
	}
	f, ok := w.(http.Flusher)
	if !ok {
		fail(w, 500, "stream unavailable")
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("X-Accel-Buffering", "no")
	w.Header().Set("Cache-Control", "no-cache, no-transform")
	w.WriteHeader(200)
	tick := time.NewTicker(2 * time.Second)
	defer tick.Stop()
	last := ""
	for {
		if _, ok := s.session(r); !ok {
			fmt.Fprint(w, "event: expired\ndata: {}\n\n")
			f.Flush()
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		res, e := s.upstream(ctx, "GET", "/servers/"+id+"/logs", nil)
		event := "snapshot"
		data := []byte(`{"error":"console unavailable; reconnecting"}`)
		if e == nil {
			if res.StatusCode == 200 {
				data, e = io.ReadAll(io.LimitReader(res.Body, 2<<20))
			} else {
				event = "unavailable"
			}
			res.Body.Close()
		} else {
			event = "unavailable"
		}
		cancel()
		if e != nil {
			event = "unavailable"
			data = []byte(`{"error":"console unavailable; reconnecting"}`)
		}
		next := event + string(data)
		http.NewResponseController(w).SetWriteDeadline(time.Now().Add(10 * time.Second))
		if next != last {
			fmt.Fprintf(w, "event: %s\ndata: %s\n\n", event, strings.TrimSpace(string(data)))
			last = next
		} else {
			fmt.Fprint(w, ": heartbeat\n\n")
		}
		f.Flush()
		select {
		case <-r.Context().Done():
			return
		case <-tick.C:
		}
	}
}
func DBPath() string {
	if v := os.Getenv("DB_PATH"); v != "" {
		return v
	}
	return "/state/panel/panel.db"
}
