package control

import (
	"archive/zip"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"

	"gopkg.in/yaml.v3"
)

const MaxPluginBytes = 64 << 20

var pluginFilename = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9 ._+()-]{0,119}\.jar$`)
var operatorUUID = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

type Plugin struct {
	Filename string `json:"filename"`
	Name     string `json:"name"`
	Version  string `json:"version"`
	Bytes    int64  `json:"bytes"`
	Managed  bool   `json:"managed"`
	Problem  string `json:"problem,omitempty"`
}

func managedPlugin(filename, name string) bool {
	return strings.EqualFold(filename, "nexus-arena-engine.jar") || strings.EqualFold(name, "NexusArenaEngine") || strings.EqualFold(filename, "network-display.jar") || strings.EqualFold(filename, "lobby-selector.jar") || strings.EqualFold(name, "VampyuyuNetwork") || strings.EqualFold(name, "VampyuyuLobby")
}
func pluginInfo(file *os.File) (Plugin, error) {
	info, e := file.Stat()
	if e != nil {
		return Plugin{}, e
	}
	out := Plugin{Bytes: info.Size()}
	if info.Size() > MaxPluginBytes {
		return out, errors.New("JAR exceeds 64 MiB")
	}
	z, e := zip.NewReader(file, info.Size())
	if e != nil {
		return out, errors.New("Not a valid JAR archive")
	}
	for _, name := range []string{"paper-plugin.yml", "plugin.yml"} {
		f, e := z.Open(name)
		if e != nil {
			continue
		}
		data, e := io.ReadAll(io.LimitReader(f, 65537))
		f.Close()
		if e != nil || len(data) > 65536 {
			return out, errors.New("Plugin metadata too large")
		}
		var meta struct {
			Name    string `yaml:"name"`
			Version string `yaml:"version"`
		}
		if yaml.Unmarshal(data, &meta) != nil || len(meta.Name) == 0 || len(meta.Name) > 128 || len(meta.Version) > 128 {
			return out, errors.New("Invalid plugin metadata")
		}
		out.Name = meta.Name
		out.Version = meta.Version
		return out, nil
	}
	return out, errors.New("JAR must contain plugin.yml or paper-plugin.yml")
}
func (s *Server) pluginRoot(id string) (*os.Root, error) {
	if !Valid(id) || id == "velocity" || s.DataRoot == "" {
		return nil, errors.New("Paper server plugin directory unavailable")
	}
	server, e := os.OpenRoot(filepath.Join(s.DataRoot, id))
	if e != nil {
		return nil, e
	}
	defer server.Close()
	info, e := server.Lstat("plugins")
	if e != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return nil, errors.New("Plugin directory is missing or is a link")
	}
	return server.OpenRoot("plugins")
}
func (s *Server) serverLock(id string) *sync.Mutex {
	for i, v := range IDs {
		if v == id {
			return &s.locks[i]
		}
	}
	panic("validated server required")
}
func (s *Server) pluginRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /servers/{id}/plugins", func(w http.ResponseWriter, r *http.Request) {
		root, e := s.pluginRoot(r.PathValue("id"))
		if e != nil {
			JSON(w, 503, map[string]string{"error": "Plugin directory unavailable"})
			return
		}
		defer root.Close()
		dir, e := root.Open(".")
		if e != nil {
			JSON(w, 503, map[string]string{"error": "Plugin directory unavailable"})
			return
		}
		defer dir.Close()
		entries, e := dir.ReadDir(-1)
		if e != nil {
			JSON(w, 503, map[string]string{"error": "Cannot list plugins"})
			return
		}
		out := []Plugin{}
		for _, entry := range entries {
			if !strings.HasSuffix(strings.ToLower(entry.Name()), ".jar") {
				continue
			}
			p := Plugin{Filename: entry.Name(), Managed: managedPlugin(entry.Name(), "")}
			if !entry.Type().IsRegular() {
				p.Problem = "Not a regular file"
				out = append(out, p)
				continue
			}
			file, e := root.Open(entry.Name())
			if e == nil {
				meta, err := pluginInfo(file)
				file.Close()
				p.Name = meta.Name
				p.Version = meta.Version
				p.Bytes = meta.Bytes
				p.Managed = p.Managed || managedPlugin("", meta.Name)
				if err != nil {
					p.Problem = err.Error()
				}
			} else {
				p.Problem = "Cannot read JAR"
			}
			out = append(out, p)
		}
		JSON(w, 200, out)
	})
	mux.HandleFunc("POST /servers/{id}/plugins/{filename}", func(w http.ResponseWriter, r *http.Request) {
		id, name := r.PathValue("id"), r.PathValue("filename")
		if !Valid(id) || id == "velocity" || !pluginFilename.MatchString(name) || managedPlugin(name, "") {
			JSON(w, 400, map[string]string{"error": "Invalid or managed plugin filename"})
			return
		}
		lock := s.serverLock(id)
		lock.Lock()
		defer lock.Unlock()
		root, e := s.pluginRoot(id)
		if e != nil {
			JSON(w, 503, map[string]string{"error": "Plugin directory unavailable"})
			return
		}
		defer root.Close()
		random := make([]byte, 16)
		if _, e = rand.Read(random); e != nil {
			JSON(w, 500, map[string]string{"error": "Upload unavailable"})
			return
		}
		temp := ".upload-" + hex.EncodeToString(random)
		f, e := root.OpenFile(temp, os.O_CREATE|os.O_EXCL|os.O_RDWR, 0640)
		if e != nil {
			JSON(w, 503, map[string]string{"error": "Cannot stage upload"})
			return
		}
		defer root.Remove(temp)
		defer f.Close()
		_, e = io.Copy(f, http.MaxBytesReader(w, r.Body, MaxPluginBytes))
		if e != nil {
			JSON(w, 413, map[string]string{"error": "Upload failed or exceeds 64 MiB"})
			return
		}
		meta, e := pluginInfo(f)
		if e != nil || managedPlugin("", meta.Name) {
			JSON(w, 400, map[string]string{"error": "Invalid JAR or reserved network plugin"})
			return
		}
		// Never replace an existing JAR; a stopped-server delete is required for updates.
		dir, _ := root.Open(".")
		if dir == nil {
			JSON(w, 503, map[string]string{"error": "Cannot inspect installed plugins"})
			return
		}
		entries, e := dir.ReadDir(-1)
		dir.Close()
		if e != nil {
			JSON(w, 503, map[string]string{"error": "Cannot inspect installed plugins"})
			return
		}
		for _, entry := range entries {
			if entry.Name() == name {
				JSON(w, 409, map[string]string{"error": "Filename already installed; stop server and remove it first"})
				return
			}
			if !entry.Type().IsRegular() || !strings.HasSuffix(entry.Name(), ".jar") {
				continue
			}
			existing, e := root.Open(entry.Name())
			if e != nil {
				continue
			}
			other, e := pluginInfo(existing)
			existing.Close()
			if e == nil && strings.EqualFold(other.Name, meta.Name) {
				JSON(w, 409, map[string]string{"error": "This plugin is already installed under another filename"})
				return
			}
		}
		if f.Sync() != nil {
			JSON(w, 503, map[string]string{"error": "Cannot persist upload"})
			return
		}
		if e = root.Link(temp, name); e != nil {
			JSON(w, 409, map[string]string{"error": "Cannot install JAR; destination may already exist"})
			return
		}
		meta.Filename = name
		JSON(w, 201, map[string]any{"plugin": meta, "restartRequired": true})
	})
	mux.HandleFunc("DELETE /servers/{id}/plugins/{filename}", func(w http.ResponseWriter, r *http.Request) {
		id, name := r.PathValue("id"), r.PathValue("filename")
		if !Valid(id) || id == "velocity" || !pluginFilename.MatchString(name) || managedPlugin(name, "") {
			JSON(w, 400, map[string]string{"error": "Invalid or managed plugin filename"})
			return
		}
		lock := s.serverLock(id)
		lock.Lock()
		defer lock.Unlock()
		status, e := s.Docker.Status(r.Context(), id)
		if e != nil {
			JSON(w, 503, map[string]string{"error": "Server status unavailable"})
			return
		}
		if status.State != "exited" && status.State != "created" {
			JSON(w, 409, map[string]string{"error": "Stop the server before removing a plugin"})
			return
		}
		root, e := s.pluginRoot(id)
		if e != nil {
			JSON(w, 503, map[string]string{"error": "Plugin directory unavailable"})
			return
		}
		defer root.Close()
		info, e := root.Lstat(name)
		if e != nil || !info.Mode().IsRegular() {
			JSON(w, 404, map[string]string{"error": "Plugin file not found"})
			return
		}
		f, e := root.Open(name)
		if e != nil {
			JSON(w, 503, map[string]string{"error": "Cannot inspect plugin"})
			return
		}
		meta, _ := pluginInfo(f)
		f.Close()
		if managedPlugin(name, meta.Name) {
			JSON(w, 403, map[string]string{"error": "Network plugins are managed by the server image"})
			return
		}
		if e = root.Remove(name); e != nil {
			JSON(w, 503, map[string]string{"error": "Cannot remove plugin"})
			return
		}
		JSON(w, 200, map[string]bool{"ok": true})
	})
	mux.HandleFunc("POST /servers/{id}/operator", func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		if !Valid(id) || id == "velocity" {
			JSON(w, 400, map[string]string{"error": "Paper server required"})
			return
		}
		var in struct {
			UUID    string `json:"uuid"`
			Enabled bool   `json:"enabled"`
		}
		d := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024))
		d.DisallowUnknownFields()
		if d.Decode(&in) != nil || !operatorUUID.MatchString(in.UUID) || in.UUID == "00000000-0000-0000-0000-000000000000" {
			JSON(w, 400, map[string]string{"error": "Invalid operator target"})
			return
		}
		lock := s.serverLock(id)
		lock.Lock()
		defer lock.Unlock()
		output, e := RCON(r.Context(), id+":25575", s.Passwords[id], fmt.Sprintf("vampyuyunetwork:vampyuyuoperator %s %t", in.UUID, in.Enabled))
		if e != nil {
			JSON(w, 503, map[string]string{"error": "Operator update unavailable"})
			return
		}
		if !strings.Contains(output, "Operator status updated:") {
			JSON(w, 503, map[string]string{"error": "Network plugin did not confirm the operator update"})
			return
		}
		JSON(w, 200, map[string]any{"ok": true, "uuid": in.UUID, "enabled": in.Enabled})
	})
}
