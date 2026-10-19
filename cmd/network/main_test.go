package main

import (
	"gopkg.in/yaml.v3"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestConfigurePreservesAndGuardsVersion(t *testing.T) {
	root := t.TempDir()
	secrets := t.TempDir()
	os.WriteFile(filepath.Join(secrets, "forwarding_secret"), []byte(strings.Repeat("a", 64)), 0600)
	os.MkdirAll(filepath.Join(root, "creative", "config"), 0750)
	original := []byte("custom-setting: keep\nproxies:\n  velocity:\n    enabled: false\n")
	filename := filepath.Join(root, "creative", "config", "paper-global.yml")
	os.WriteFile(filename, original, 0640)
	if e := configure(root, secrets, "1.21.4"); e != nil {
		t.Fatal(e)
	}
	b, _ := os.ReadFile(filename)
	var m map[string]any
	yaml.Unmarshal(b, &m)
	if m["custom-setting"] != "keep" {
		t.Fatal("lost custom settings")
	}
	if object(object(m, "proxies"), "velocity")["secret"] != strings.Repeat("a", 64) {
		t.Fatal("secret mismatch")
	}
	backup, _ := os.ReadFile(filename + ".before-network")
	if string(backup) != string(original) {
		t.Fatal("original not preserved")
	}
	if e := configure(root, secrets, "1.21.5"); e == nil {
		t.Fatal("version change accepted")
	}
	after, _ := os.ReadFile(filename)
	if string(after) != string(b) {
		t.Fatal("modified configuration on refused migration")
	}
}
