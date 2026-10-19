package control

import (
	"bytes"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"
)

var gameUUID = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var gameActions = map[string]bool{"lobby-build": true, "arena-save": true, "arena-delete": true, "arena-start": true, "arena-stop": true, "arena-announce": true, "arena-reload": true, "arena-join": true, "arena-leave": true, "arena-ban": true, "arena-kick": true, "arena-unban": true, "gamemode": true, "restore-inventory": true, "heal": true, "feed": true, "clear-effects": true, "teleport-spawn": true, "set-spawn": true, "kick": true, "ban": true, "pardon": true, "pardon-name": true, "whitelist-add": true, "whitelist-remove": true, "broadcast": true, "save": true, "whitelist": true, "difficulty": true, "time": true, "weather": true, "pvp": true, "gamerule": true}

func ValidGameAction(action string) bool { return gameActions[action] }
func (s *Server) gameProxy(w http.ResponseWriter, r *http.Request, id, method, path string, body io.Reader) {
	if !Valid(id) || id == "velocity" || len(s.GameToken) < 32 {
		JSON(w, 503, map[string]string{"error": "Minecraft management bridge unavailable"})
		return
	}
	cid, e := s.Docker.container(r.Context(), id)
	if e != nil {
		JSON(w, 503, map[string]string{"error": "Managed Minecraft container unavailable"})
		return
	}
	inspect, e := s.Docker.request(r.Context(), "GET", "/containers/"+cid+"/json")
	if e != nil {
		JSON(w, 503, map[string]string{"error": "Managed Minecraft container unavailable"})
		return
	}
	var info struct {
		NetworkSettings struct {
			Networks map[string]struct{ IPAddress string }
		}
	}
	e = json.NewDecoder(inspect.Body).Decode(&info)
	inspect.Body.Close()
	host := ""
	if e == nil && len(info.NetworkSettings.Networks) == 1 {
		for _, network := range info.NetworkSettings.Networks {
			host = network.IPAddress
		}
	}
	if net.ParseIP(host) == nil {
		JSON(w, 503, map[string]string{"error": "Managed Minecraft network unavailable"})
		return
	}
	port := "8084"
	if strings.HasPrefix(path, "/arenas") {
		port = "8085"
	}
	request, e := http.NewRequestWithContext(r.Context(), method, "http://"+net.JoinHostPort(host, port)+path, body)
	if e != nil {
		JSON(w, 503, map[string]string{"error": "Minecraft request failed"})
		return
	}
	request.Header.Set("Authorization", "Bearer "+s.GameToken)
	request.Header.Set("Content-Type", "application/json")
	client := s.GameHTTP
	if client == nil {
		client = &http.Client{Timeout: 20 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	}
	response, e := client.Do(request)
	if e != nil {
		JSON(w, 503, map[string]string{"error": "Minecraft management bridge unavailable; the server must be running"})
		return
	}
	defer response.Body.Close()
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(response.StatusCode)
	io.Copy(w, io.LimitReader(response.Body, 2<<20))
}
func (s *Server) gameRoutes(mux *http.ServeMux) {
	for _, kind := range []string{"state", "materials", "arenas"} {
		mux.HandleFunc("GET /servers/{id}/game/"+kind, func(w http.ResponseWriter, r *http.Request) {
			s.gameProxy(w, r, r.PathValue("id"), "GET", "/"+kind, nil)
		})
	}
	for _, method := range []string{"GET", "PUT"} {
		mux.HandleFunc(method+" /servers/{id}/game/players/{uuid}/{container}", func(w http.ResponseWriter, r *http.Request) {
			id, uuid, container := r.PathValue("id"), r.PathValue("uuid"), r.PathValue("container")
			if !gameUUID.MatchString(uuid) || (container != "inventory" && container != "ender" && !(method == "GET" && container == "backups")) {
				JSON(w, 400, map[string]string{"error": "Invalid player or container"})
				return
			}
			var body io.Reader
			if method == "PUT" {
				body = http.MaxBytesReader(w, r.Body, 8192)
			}
			lock := s.serverLockSafe(id)
			if lock == nil {
				JSON(w, 400, map[string]string{"error": "Unknown Paper server"})
				return
			}
			if method == "PUT" {
				lock.Lock()
				defer lock.Unlock()
			}
			s.gameProxy(w, r, id, method, "/players/"+uuid+"/"+container, body)
		})
	}
	mux.HandleFunc("POST /servers/{id}/game/action", func(w http.ResponseWriter, r *http.Request) {
		var input map[string]json.RawMessage
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 65536)).Decode(&input) != nil {
			JSON(w, 400, map[string]string{"error": "Invalid action"})
			return
		}
		var action string
		json.Unmarshal(input["action"], &action)
		if !ValidGameAction(action) {
			JSON(w, 400, map[string]string{"error": "Unsupported Minecraft operation"})
			return
		}
		if raw, ok := input["uuid"]; ok {
			var uuid string
			if json.Unmarshal(raw, &uuid) != nil || !gameUUID.MatchString(uuid) {
				JSON(w, 400, map[string]string{"error": "Invalid UUID"})
				return
			}
		}
		body, _ := json.Marshal(input)
		id := r.PathValue("id")
		lock := s.serverLockSafe(id)
		if lock == nil {
			JSON(w, 400, map[string]string{"error": "Unknown Paper server"})
			return
		}
		lock.Lock()
		defer lock.Unlock()
		path := "/action"
		if strings.HasPrefix(action, "arena-") {
			path = "/arenas/action"
		}
		s.gameProxy(w, r, id, "POST", path, bytes.NewReader(body))
	})
}
func (s *Server) serverLockSafe(id string) *sync.Mutex {
	if !Valid(id) || strings.EqualFold(id, "velocity") {
		return nil
	}
	return s.serverLock(id)
}
