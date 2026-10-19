package panel

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"

	"github.com/garder500/minecraft-bungee/internal/control"
	"github.com/garder500/minecraft-bungee/internal/players"
)

func (s *Server) gameRoutes(mux *http.ServeMux, protected func(func(http.ResponseWriter, *http.Request, session)) http.HandlerFunc) {
	for _, kind := range []string{"state", "materials", "arenas"} {
		mux.HandleFunc("GET /api/v1/servers/{id}/game/"+kind, protected(func(w http.ResponseWriter, r *http.Request, v session) {
			id := r.PathValue("id")
			if !control.Valid(id) || id == "velocity" {
				fail(w, 400, "Paper server required")
				return
			}
			s.relay(w, r, "GET", "/servers/"+id+"/game/"+kind, nil)
		}))
	}
	for _, method := range []string{"GET", "PUT"} {
		mux.HandleFunc(method+" /api/v1/servers/{id}/game/players/{uuid}/{container}", protected(func(w http.ResponseWriter, r *http.Request, v session) {
			id, uuid, container := r.PathValue("id"), r.PathValue("uuid"), r.PathValue("container")
			if !control.Valid(id) || id == "velocity" || !players.ValidUUID(uuid) || (container != "inventory" && container != "ender" && !(method == "GET" && container == "backups")) {
				fail(w, 400, "invalid server, player or container")
				return
			}
			var exists int
			if s.DB.QueryRow("SELECT COUNT(*) FROM players WHERE uuid=?", uuid).Scan(&exists) != nil || exists == 0 {
				fail(w, 404, "player not found")
				return
			}
			route := "/servers/" + id + "/game/players/" + uuid + "/" + container
			if method == "GET" {
				s.relay(w, r, "GET", route, nil)
				return
			}
			var edit struct {
				Revision string `json:"revision"`
				Slot     int    `json:"slot"`
				Material string `json:"material"`
				Amount   int    `json:"amount"`
			}
			if !decode(w, r, &edit) {
				return
			}
			if len(edit.Revision) != 64 || edit.Slot < 0 || edit.Slot > 40 || len(edit.Material) > 128 || edit.Amount < 0 || edit.Amount > 99 {
				fail(w, 400, "invalid inventory edit")
				return
			}
			body, _ := json.Marshal(edit)
			detail, _ := json.Marshal(map[string]any{"uuid": uuid, "container": container, "slot": edit.Slot})
			s.auditRelay(w, r, v, id, "inventory-edit:"+string(detail), "PUT", route, strings.NewReader(string(body)))
		}))
	}
	mux.HandleFunc("POST /api/v1/servers/{id}/game/action", protected(func(w http.ResponseWriter, r *http.Request, v session) {
		id := r.PathValue("id")
		if !control.Valid(id) || id == "velocity" {
			fail(w, 400, "Paper server required")
			return
		}
		var input map[string]json.RawMessage
		if !decodeSized(w, r, &input, 65536) {
			return
		}
		var action string
		json.Unmarshal(input["action"], &action)
		if !control.ValidGameAction(action) {
			fail(w, 400, "unsupported Minecraft operation")
			return
		}
		detail := map[string]string{"action": action}
		if strings.HasPrefix(action, "arena-") {
			var arenaId string
			json.Unmarshal(input["arenaId"], &arenaId)
			if arenaId != "" && (len(arenaId) > 40 || strings.ContainsAny(arenaId, "/\\\n\r")) {
				fail(w, 400, "invalid arena ID")
				return
			}
			detail["arenaId"] = arenaId
		}
		if raw, ok := input["uuid"]; ok {
			var uuid string
			json.Unmarshal(raw, &uuid)
			if !players.ValidUUID(uuid) {
				fail(w, 400, "invalid UUID")
				return
			}
			var count int
			s.DB.QueryRow("SELECT COUNT(*) FROM players WHERE uuid=?", uuid).Scan(&count)
			if count != 1 && action != "whitelist-remove" && action != "arena-unban" {
				fail(w, 404, "player not found")
				return
			}
			detail["uuid"] = uuid
		}
		body, _ := json.Marshal(input)
		audit, _ := json.Marshal(detail)
		s.auditRelay(w, r, v, id, "game:"+string(audit), "POST", "/servers/"+id+"/game/action", strings.NewReader(string(body)))
	}))
	mux.HandleFunc("GET /api/v1/activity", protected(func(w http.ResponseWriter, r *http.Request, v session) {
		before := int64(0)
		if raw := r.URL.Query().Get("before"); raw != "" {
			var e error
			before, e = strconv.ParseInt(raw, 10, 64)
			if e != nil || before < 0 {
				fail(w, 400, "invalid cursor")
				return
			}
		}
		server := r.URL.Query().Get("server")
		if server != "" && server != "network" && !control.Valid(server) {
			fail(w, 400, "invalid server")
			return
		}
		rows, e := s.DB.Query("SELECT id,at,username,server,action,result FROM audit WHERE (?=0 OR id<?) AND (?='' OR server=?) ORDER BY id DESC LIMIT 100", before, before, server, server)
		if e != nil {
			fail(w, 500, "activity unavailable")
			return
		}
		defer rows.Close()
		out := []map[string]any{}
		for rows.Next() {
			var id int64
			var at, user, server, action string
			var result int
			if e = rows.Scan(&id, &at, &user, &server, &action, &result); e != nil {
				fail(w, 500, "activity unavailable")
				return
			}
			out = append(out, map[string]any{"id": id, "at": at, "actor": user, "server": server, "action": action, "result": result})
		}
		control.JSON(w, 200, out)
	}))
}
