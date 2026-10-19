package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"github.com/garder500/minecraft-bungee/internal/archive"
	"gopkg.in/yaml.v3"
	"log"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

func main() {
	if len(os.Args) < 2 {
		log.Fatal("usage: network secrets|inspect|import|configure")
	}
	f := flag.NewFlagSet(os.Args[1], flag.ExitOnError)
	root := f.String("root", "/state", "state directory")
	source := f.String("zip", "", "archive path")
	dest := f.String("dest", "/state/creative", "import destination")
	version := f.String("version", "", "reviewed Minecraft version")
	secretDir := f.String("secrets", "/secrets", "secret directory")
	f.Parse(os.Args[2:])
	switch os.Args[1] {
	case "secrets":
		if e := os.MkdirAll(*secretDir, 0700); e != nil {
			log.Fatal(e)
		}
		for _, n := range []string{"controller_token", "role_bridge_token", "arena_redis_password", "forwarding_secret", "lobby_rcon", "creative_rcon", "survival_rcon"} {
			p := filepath.Join(*secretDir, n)
			if _, e := os.Lstat(p); e == nil {
				continue
			} else if !os.IsNotExist(e) {
				log.Fatal(e)
			}
			b := make([]byte, 32)
			if _, e := rand.Read(b); e != nil {
				log.Fatal(e)
			}
			h, e := os.OpenFile(p, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
			if e != nil {
				log.Fatal(e)
			}
			_, e = h.WriteString(hex.EncodeToString(b) + "\n")
			h.Close()
			if e != nil {
				log.Fatal(e)
			}
		}
		fmt.Println("Secrets initialized; existing files preserved")
	case "inspect":
		r, e := archive.Inspect(*source)
		if e != nil {
			log.Fatal(e)
		}
		b, _ := json.MarshalIndent(r, "", "  ")
		fmt.Println(string(b))
		if !r.Compatible {
			os.Exit(2)
		}
	case "import":
		r, e := archive.Import(*source, *dest, *version)
		if e != nil {
			log.Fatal(e)
		}
		b, _ := json.MarshalIndent(r, "", "  ")
		fmt.Println(string(b))
		fmt.Println("Imported without changing server configuration. Review and run configure before startup.")
	case "configure":
		if *version == "" {
			log.Fatal("--version is required")
		}
		if e := configure(*root, *secretDir, *version); e != nil {
			log.Fatal(e)
		}
	default:
		log.Fatal("unknown command")
	}
}
func object(m map[string]any, key string) map[string]any {
	v, ok := m[key].(map[string]any)
	if !ok {
		v = map[string]any{}
		m[key] = v
	}
	return v
}
func patchYAML(filename string, update func(map[string]any)) error {
	m := map[string]any{}
	original, err := os.ReadFile(filename)
	if err == nil {
		if err = yaml.Unmarshal(original, &m); err != nil {
			return err
		}
		if m == nil {
			m = map[string]any{}
		}
		backup := filename + ".before-network"
		if _, err := os.Stat(backup); os.IsNotExist(err) {
			if err := os.WriteFile(backup, original, 0640); err != nil {
				return err
			}
		}
	} else if !os.IsNotExist(err) {
		return err
	}
	update(m)
	b, err := yaml.Marshal(m)
	if err != nil {
		return err
	}
	return os.WriteFile(filename, b, 0640)
}
func configure(root, secrets, version string) error {
	secret, err := os.ReadFile(filepath.Join(secrets, "forwarding_secret"))
	if err != nil {
		return err
	}
	token := strings.TrimSpace(string(secret))
	if len(token) != 64 {
		return fmt.Errorf("forwarding secret must be 64 hex characters")
	}
	if _, err = hex.DecodeString(token); err != nil {
		return err
	}
	// Refuse version changes before writing any configuration or world data.
	for _, id := range []string{"lobby", "creative", "survival"} {
		dir := filepath.Join(root, id)
		if b, e := os.ReadFile(filepath.Join(dir, ".network-version")); e == nil && strings.TrimSpace(string(b)) != version {
			return fmt.Errorf("%s: version change requires a separately reviewed migration", id)
		}
		if b, e := os.ReadFile(filepath.Join(dir, "version_history.json")); e == nil {
			var history struct {
				Current string `json:"currentVersion"`
			}
			if json.Unmarshal(b, &history) != nil {
				return fmt.Errorf("%s: invalid version history", id)
			}
			if history.Current != "" && regexp.MustCompile(`\b(1\.[0-9]+(?:\.[0-9]+)?|26\.[0-9]+(?:\.[0-9]+)?)\b`).FindString(history.Current) != version {
				return fmt.Errorf("%s: existing runtime version differs; migration refused", id)
			}
		}
	}
	for _, id := range []string{"lobby", "creative", "survival"} {
		dir := filepath.Join(root, id)
		if err = os.MkdirAll(filepath.Join(dir, "config"), 0750); err != nil {
			return err
		}
		err = patchYAML(filepath.Join(dir, "config", "paper-global.yml"), func(m map[string]any) {
			v := object(object(m, "proxies"), "velocity")
			v["enabled"] = true
			v["online-mode"] = true
			v["secret"] = token
		})
		if err != nil {
			return err
		}
		err = patchYAML(filepath.Join(dir, "spigot.yml"), func(m map[string]any) { object(m, "settings")["bungeecord"] = false })
		if err != nil {
			return err
		}
		if err = os.WriteFile(filepath.Join(dir, ".network-version"), []byte(version+"\n"), 0640); err != nil {
			return err
		}
	}
	if err = os.MkdirAll(filepath.Join(root, "velocity"), 0750); err != nil {
		return err
	}
	if err = os.WriteFile(filepath.Join(root, "velocity", "forwarding.secret"), []byte(token+"\n"), 0640); err != nil {
		return err
	}
	for _, directory := range []string{"panel", "redis"} {
		if err = os.MkdirAll(filepath.Join(root, directory), 0750); err != nil {
			return err
		}
	}
	fmt.Printf("Configured modern forwarding for Minecraft %s. Original YAML saved as *.before-network.\n", version)
	return nil
}
