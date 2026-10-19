package archive

import (
	"archive/zip"
	"os"
	"path/filepath"
	"testing"
)

func fixture(t *testing.T, entries map[string]string) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "server.zip")
	f, e := os.Create(p)
	if e != nil {
		t.Fatal(e)
	}
	z := zip.NewWriter(f)
	for n, b := range entries {
		w, e := z.Create(n)
		if e != nil {
			t.Fatal(e)
		}
		w.Write([]byte(b))
	}
	z.Close()
	f.Close()
	return p
}
func good() map[string]string {
	return map[string]string{"Server/server.properties": "online-mode=true\n", "Server/config/paper-global.yml": "proxies: {}\n", "Server/version_history.json": `{"currentVersion":"1.21.4-232-12d8fe0"}`, "Server/world/level.dat": "world", "Server/world/playerdata/player.dat": "uuid", "Server/plugins/plugin.jar": "plugin"}
}
func TestImportPreservesAndRefusesOverwrite(t *testing.T) {
	zipfile := fixture(t, good())
	r, e := Inspect(zipfile)
	if e != nil || !r.Compatible || r.Version != "1.21.4" || r.Java != 21 {
		t.Fatal(r, e)
	}
	dest := filepath.Join(t.TempDir(), "creative")
	if _, e = Import(zipfile, dest, "1.21.4"); e != nil {
		t.Fatal(e)
	}
	b, e := os.ReadFile(filepath.Join(dest, "server.properties"))
	if e != nil || string(b) != "online-mode=true\n" {
		t.Fatal(string(b), e)
	}
	if _, e = Import(zipfile, dest, "1.21.4"); e == nil {
		t.Fatal("overwritten")
	}
	if _, e = os.Stat(zipfile); e != nil {
		t.Fatal("source removed")
	}
}
func TestUnsafePaths(t *testing.T) {
	for _, name := range []string{"../escape", "Server/../../escape", "/absolute", "C:/escape", "Server\\escape", "Server/./file"} {
		t.Run(name, func(t *testing.T) {
			entries := good()
			entries[name] = "evil"
			if _, e := Inspect(fixture(t, entries)); e == nil {
				t.Fatal("unsafe path accepted")
			}
		})
	}
}
func TestIncompatibleAndMismatch(t *testing.T) {
	entries := good()
	delete(entries, "Server/config/paper-global.yml")
	zipfile := fixture(t, entries)
	dest := filepath.Join(t.TempDir(), "creative")
	if _, e := Import(zipfile, dest, "1.21.4"); e == nil {
		t.Fatal("non-Paper imported")
	}
	if _, e := os.Stat(dest); !os.IsNotExist(e) {
		t.Fatal("destination modified")
	}
	if _, e := Import(fixture(t, good()), dest, "1.21.5"); e == nil {
		t.Fatal("version mismatch accepted")
	}
}
func TestSymlinksAndDuplicates(t *testing.T) {
	for _, sym := range []bool{true, false} {
		p := filepath.Join(t.TempDir(), "unsafe.zip")
		f, _ := os.Create(p)
		z := zip.NewWriter(f)
		h := &zip.FileHeader{Name: "server.properties", Method: zip.Store}
		if sym {
			h.SetMode(os.ModeSymlink | 0777)
		}
		w, _ := z.CreateHeader(h)
		w.Write([]byte("target"))
		if !sym {
			z.Create("server.properties")
		}
		z.Close()
		f.Close()
		if _, e := Inspect(p); e == nil {
			t.Fatal("unsafe entry accepted")
		}
	}
}

func TestDependencyVersionsAreNotMinecraftVersions(t *testing.T) {
	entries := good()
	entries["Server/libraries/spark-paper-1.10.119.jar"] = "dependency"
	entries["Server/plugins/.paper-remapped/internal.jar"] = "cache"
	report, err := Inspect(fixture(t, entries))
	if err != nil || !report.Compatible || report.Version != "1.21.4" || len(report.Plugins) != 1 {
		t.Fatal(report, err)
	}
}
