package control

import (
	"archive/zip"
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func jar(t *testing.T, name string) []byte {
	t.Helper()
	var b bytes.Buffer
	z := zip.NewWriter(&b)
	w, _ := z.Create("plugin.yml")
	w.Write([]byte("name: " + name + "\nversion: 1.0\n"))
	z.Close()
	return b.Bytes()
}
func managementServer(t *testing.T, state *string) *Server {
	t.Helper()
	root := t.TempDir()
	if e := os.MkdirAll(filepath.Join(root, "creative", "plugins"), 0750); e != nil {
		t.Fatal(e)
	}
	d := fakeDocker(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "/containers/json") {
			io.WriteString(w, `[{"Id":"managed"}]`)
			return
		}
		if strings.HasSuffix(r.URL.Path, "/json") {
			io.WriteString(w, `{"State":{"Status":"`+*state+`"}}`)
			return
		}
		t.Errorf("unexpected Docker operation %s", r.URL)
		w.WriteHeader(500)
	})
	return &Server{Docker: d, DataRoot: root, Token: strings.Repeat("x", 64)}
}
func controllerRequest(s *Server, method, path string, body []byte) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, bytes.NewReader(body))
	r.Header.Set("Authorization", "Bearer "+s.Token)
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	return w
}
func TestPluginValidationAndManagedProtection(t *testing.T) {
	state := "running"
	s := managementServer(t, &state)
	for _, test := range []struct {
		name string
		body []byte
		code int
	}{{"Test.jar", jar(t, "Test"), 201}, {"Test.jar", jar(t, "Test"), 409}, {"Different.jar", jar(t, "Test"), 409}, {"Fake.jar", []byte("not a jar"), 400}, {"network-display.jar", jar(t, "Test"), 400}, {"Disguise.jar", jar(t, "VampyuyuNetwork"), 400}, {"nexus-arena-engine.jar", jar(t, "Test"), 400}, {"ArenaDisguise.jar", jar(t, "NexusArenaEngine"), 400}, {"bad%5cpath.jar", jar(t, "Test"), 400}} {
		w := controllerRequest(s, "POST", "/servers/creative/plugins/"+test.name, test.body)
		if w.Code != test.code {
			t.Fatalf("%s: %d %s", test.name, w.Code, w.Body.String())
		}
	}
	if w := controllerRequest(s, "DELETE", "/servers/creative/plugins/Test.jar", nil); w.Code != 409 {
		t.Fatal(w.Code, w.Body.String())
	}
	state = "exited"
	if w := controllerRequest(s, "DELETE", "/servers/creative/plugins/Test.jar", nil); w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	outside := filepath.Join(t.TempDir(), "outside.jar")
	os.WriteFile(outside, jar(t, "Outside"), 0600)
	os.Symlink(outside, filepath.Join(s.DataRoot, "creative/plugins/Link.jar"))
	if w := controllerRequest(s, "DELETE", "/servers/creative/plugins/Link.jar", nil); w.Code != 404 {
		t.Fatal(w.Code)
	}
	if _, e := os.Stat(outside); e != nil {
		t.Fatal("symlink target was removed")
	}
}
func TestPluginDirectoryCannotEscape(t *testing.T) {
	state := "exited"
	s := managementServer(t, &state)
	dir := filepath.Join(s.DataRoot, "creative/plugins")
	os.Remove(dir)
	outside := t.TempDir()
	os.Symlink(outside, dir)
	w := controllerRequest(s, "POST", "/servers/creative/plugins/Test.jar", jar(t, "Test"))
	if w.Code != 503 {
		t.Fatal(w.Code)
	}
	entries, _ := os.ReadDir(outside)
	if len(entries) != 0 {
		t.Fatal("upload escaped")
	}
}
func TestOperatorRejectsInjectionBeforeRCON(t *testing.T) {
	state := "running"
	s := managementServer(t, &state)
	for _, uuid := range []string{"Alice;op Bob", "../ops.json", "00000000-0000-0000-0000-000000000000"} {
		w := controllerRequest(s, "POST", "/servers/creative/operator", []byte(`{"uuid":"`+uuid+`","enabled":true}`))
		if w.Code != 400 {
			t.Fatal(w.Code, w.Body.String())
		}
	}
}
func TestMetricsUsesRealDeltasAndExcludesCache(t *testing.T) {
	root := t.TempDir()
	os.Mkdir(filepath.Join(root, "creative"), 0750)
	os.WriteFile(filepath.Join(root, "creative", "world.dat"), []byte("world"), 0600)
	d := fakeDocker(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.Contains(r.URL.Path, "/containers/json"):
			io.WriteString(w, `[{"Id":"managed"}]`)
		case strings.HasSuffix(r.URL.Path, "/json"):
			io.WriteString(w, `{"State":{"Running":true,"StartedAt":"2026-10-19T20:00:00Z"},"RestartCount":2}`)
		case strings.HasSuffix(r.URL.Path, "/stats"):
			io.WriteString(w, `{"cpu_stats":{"cpu_usage":{"total_usage":300},"system_cpu_usage":2000,"online_cpus":4},"precpu_stats":{"cpu_usage":{"total_usage":100},"system_cpu_usage":1000},"memory_stats":{"usage":1000,"limit":2000,"stats":{"inactive_file":200}},"pids_stats":{"current":12}}`)
		case strings.HasSuffix(r.URL.Path, "/top"):
			if r.URL.Query().Get("ps_args") != "-eo pid,comm,pcpu,pmem" {
				t.Fatal("arbitrary ps arguments")
			}
			io.WriteString(w, `{"Titles":["PID","COMMAND","%CPU","%MEM"],"Processes":[["123","java","10.0","2.0"]]}`)
		default:
			t.Error(r.URL)
		}
	})
	s := Server{Docker: d, DataRoot: root}
	m, e := s.metrics(context.Background(), "creative")
	if e != nil || m.CPUPercent == nil || *m.CPUPercent != 80 || m.MemoryBytes == nil || *m.MemoryBytes != 800 || m.StorageBytes == nil || *m.StorageBytes != 5 || len(m.Processes) != 1 {
		t.Fatalf("%+v %v", m, e)
	}
	if _, e = s.metrics(context.Background(), "unmanaged"); e == nil {
		t.Fatal("unmanaged metrics accepted")
	}
}
