package panel

import (
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/garder500/minecraft-bungee/internal/control"
	"github.com/garder500/minecraft-bungee/internal/players"
)

func (s *Server) auditRelay(w http.ResponseWriter, r *http.Request, v session, server, action, method, route string, body io.Reader) {
	row, e := s.DB.Exec("INSERT INTO audit(at,username,server,action,result) VALUES(?,?,?,?,?)", time.Now().UTC().Format(time.RFC3339), v.User, server, action, 0)
	if e != nil {
		fail(w, 500, "audit unavailable")
		return
	}
	id, _ := row.LastInsertId()
	status := s.relay(w, r, method, route, body)
	s.DB.Exec("UPDATE audit SET result=? WHERE id=?", status, id)
}
func (s *Server) managementRoutes(mux *http.ServeMux, protected func(func(http.ResponseWriter, *http.Request, session)) http.HandlerFunc) {
	directory := &players.Service{DB: s.DB}
	mux.HandleFunc("GET /api/v1/role-commands", protected(func(w http.ResponseWriter, r *http.Request, v session) {
		commands, e := directory.RoleCommands()
		if e != nil {
			fail(w, 500, "role commands unavailable")
			return
		}
		control.JSON(w, 200, map[string]any{"allowed": players.AllowedCommands, "roles": commands})
	}))
	mux.HandleFunc("PUT /api/v1/roles/{role}/commands", protected(func(w http.ResponseWriter, r *http.Request, v session) {
		var input struct {
			Commands []string `json:"commands"`
		}
		if !decode(w, r, &input) {
			return
		}
		if e := directory.SetCommands(r.PathValue("role"), input.Commands, v.User); e != nil {
			fail(w, 400, "invalid role commands; only ban, kick, pardon, fly and invsee are permitted")
			return
		}
		control.JSON(w, 200, map[string]bool{"ok": true})
	}))
	mux.HandleFunc("GET /api/v1/servers/{id}/metrics", protected(func(w http.ResponseWriter, r *http.Request, v session) {
		id := r.PathValue("id")
		if !control.Valid(id) {
			fail(w, 404, "unknown server")
			return
		}
		s.relay(w, r, "GET", "/servers/"+id+"/metrics", nil)
	}))
	mux.HandleFunc("GET /api/v1/servers/{id}/plugins", protected(func(w http.ResponseWriter, r *http.Request, v session) {
		id := r.PathValue("id")
		if !control.Valid(id) || id == "velocity" {
			fail(w, 400, "Paper server required")
			return
		}
		s.relay(w, r, "GET", "/servers/"+id+"/plugins", nil)
	}))
	for _, method := range []string{"POST", "DELETE"} {
		mux.HandleFunc(method+" /api/v1/servers/{id}/plugins/{filename}", protected(func(w http.ResponseWriter, r *http.Request, v session) {
			id, name := r.PathValue("id"), r.PathValue("filename")
			if !control.Valid(id) || id == "velocity" || strings.ContainsAny(name, "/\\\x00") || len(name) > 128 {
				fail(w, 400, "invalid server or filename")
				return
			}
			var body io.Reader
			if r.Method == "POST" {
				if r.ContentLength > control.MaxPluginBytes {
					fail(w, 413, "JAR exceeds 64 MiB")
					return
				}
				body = http.MaxBytesReader(w, r.Body, control.MaxPluginBytes)
			}
			detail, _ := json.Marshal(map[string]string{"filename": name})
			s.auditRelay(w, r, v, id, "plugin-"+r.Method+":"+string(detail), r.Method, "/servers/"+id+"/plugins/"+url.PathEscape(name), body)
		}))
	}
	mux.HandleFunc("POST /api/v1/players/{uuid}/operator", protected(func(w http.ResponseWriter, r *http.Request, v session) {
		uuid := r.PathValue("uuid")
		var input struct {
			Server  string `json:"server"`
			Enabled bool   `json:"enabled"`
		}
		if !decode(w, r, &input) {
			return
		}
		if !players.ValidUUID(uuid) || !control.Valid(input.Server) || input.Server == "velocity" {
			fail(w, 400, "invalid player or server")
			return
		}
		var name string
		if s.DB.QueryRow("SELECT name FROM players WHERE uuid=?", uuid).Scan(&name) != nil {
			fail(w, 404, "player not found")
			return
		}
		body, _ := json.Marshal(map[string]any{"uuid": uuid, "enabled": input.Enabled})
		s.auditRelay(w, r, v, input.Server, "operator:"+string(body), "POST", "/servers/"+input.Server+"/operator", strings.NewReader(string(body)))
	}))
}
