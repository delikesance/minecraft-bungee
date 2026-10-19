package players_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/garder500/minecraft-bungee/internal/panel"
	"github.com/garder500/minecraft-bungee/internal/players"
)

const alice = "12345678-1234-4234-8234-123456789abc"

func service(t *testing.T) *players.Service {
	t.Helper()
	db, err := panel.Open(filepath.Join(t.TempDir(), "panel.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return &players.Service{DB: db}
}
func TestRoleSurvivesWorldSwitchAndOfflineRegistration(t *testing.T) {
	s := service(t)
	result, err := s.Sync(players.Sync{Server: "lobby", Players: []players.Identity{{UUID: alice, Name: "Alice"}}}, false)
	if err != nil || result.Assignments[alice] != "player" {
		t.Fatal(result, err)
	}
	found, err := s.SetRole(alice, "owner", "admin")
	if err != nil || !found {
		t.Fatal(found, err)
	}
	// An old world cache must not revert an updated name, assigned role, or last-seen time.
	_, err = s.Sync(players.Sync{Server: "creative", Players: []players.Identity{{UUID: alice, Name: "OldAlice", LastSeen: 1}}}, true)
	if err != nil {
		t.Fatal(err)
	}
	result, err = s.Sync(players.Sync{Server: "survival", Players: []players.Identity{{UUID: alice, Name: "Alice"}}}, false)
	if err != nil || result.Assignments[alice] != "owner" || result.Online != 1 {
		t.Fatal(result, err)
	}
	listed, err := s.List("Alice", "owner", 1)
	if err != nil || listed.Total != 1 || listed.Players[0].Name != "Alice" || !listed.Players[0].Online {
		t.Fatal(listed, err)
	}
	s.DB.Exec("UPDATE player_presence SET seen=?", time.Now().Unix()-players.PresenceTTL-1)
	listed, err = s.List("", "", 1)
	if err != nil || listed.Online != 0 || listed.Players[0].Online || listed.Players[0].Role != "owner" {
		t.Fatal(listed, err)
	}
	var audit string
	s.DB.QueryRow("SELECT action FROM audit").Scan(&audit)
	if !strings.Contains(audit, `"after":"owner"`) || !strings.Contains(audit, alice) {
		t.Fatal(audit)
	}
}
func TestDefaultAndInvalidIdentity(t *testing.T) {
	s := service(t)
	bat := players.Catalog[len(players.Catalog)-1]
	if bat.Name != "Yuyu's Bat" || bat.Prefix != "Yuyu's Bat" {
		t.Fatal(bat)
	}
	for _, in := range []players.Sync{{Server: "velocity"}, {Server: "lobby", Players: []players.Identity{{UUID: "anything", Name: "Alice"}}}, {Server: "creative", Players: []players.Identity{{UUID: alice, Name: "Alice;op"}}}, {Server: "lobby", Players: []players.Identity{{UUID: alice, Name: "Alice"}, {UUID: alice, Name: "Alice"}}}} {
		if _, err := s.Sync(in, false); err == nil {
			t.Fatal("invalid identity accepted", in)
		}
	}
	if _, err := s.SetRole(alice, "op", "admin"); err == nil {
		t.Fatal("unknown role accepted")
	}
	if found, err := s.SetRole(alice, "owner", "admin"); err != nil || found {
		t.Fatal("unknown player was created")
	}
	list, err := s.List("", "", 1)
	if err != nil || list.Total != 0 {
		t.Fatal(list, err)
	}
}
func TestBridgeAuthenticationAndHistoryOnlyRegistration(t *testing.T) {
	s := service(t)
	token := strings.Repeat("x", 64)
	handler := s.BridgeHandler(token)
	body := `{"server":"creative","players":[{"uuid":"` + alice + `","name":"Alice","lastSeen":100}]}`
	for _, authenticated := range []bool{false, true} {
		r := httptest.NewRequest(http.MethodPost, "/bridge/register", strings.NewReader(body))
		if authenticated {
			r.Header.Set("Authorization", "Bearer "+token)
		}
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		if !authenticated && w.Code != 401 {
			t.Fatal(w.Code)
		}
		if authenticated {
			if w.Code != 200 {
				t.Fatal(w.Code, w.Body.String())
			}
			var result players.SyncResult
			json.Unmarshal(w.Body.Bytes(), &result)
			if result.Online != 0 {
				t.Fatal(result)
			}
		}
	}
	list, err := s.List("Alice", "", 1)
	if err != nil || list.Total != 1 || list.Players[0].Online || list.Players[0].LastSeen != 100 {
		t.Fatal(list, err)
	}
	// Search treats SQL wildcard characters literally.
	list, err = s.List("%", "", 1)
	if err != nil || list.Total != 0 {
		t.Fatal(list, err)
	}
}

func TestLimitedRoleCommandsAndOperatorSynchronization(t *testing.T) {
	s := service(t)
	for _, commands := range [][]string{{"op"}, {"deop"}, {"gamemode"}, {"minecraft:op"}, {"*"}, {"ban", "ban"}, {"fly other"}} {
		if e := s.SetCommands("mod", commands, "admin"); e == nil {
			t.Fatal("unsafe grant accepted", commands)
		}
	}
	if e := s.SetCommands("mod", []string{"kick", "fly"}, "admin"); e != nil {
		t.Fatal(e)
	}
	input := players.Sync{Server: "creative", Players: []players.Identity{{UUID: alice, Name: "Alice"}}, Operators: []players.Identity{{UUID: alice}}}
	result, e := s.Sync(input, false)
	if e != nil || len(result.Commands["mod"]) != 2 || len(result.Commands["player"]) != 0 {
		t.Fatal(result, e)
	}
	list, e := s.List("", "", 1)
	if e != nil || len(list.Players[0].Operators) != 1 || list.Players[0].Operators[0] != "creative" {
		t.Fatal(list, e)
	}
	input.Operators = nil
	s.Sync(input, false)
	list, e = s.List("", "", 1)
	if e != nil || len(list.Players[0].Operators) != 0 {
		t.Fatal("deop not synchronized", list, e)
	}
	if e = s.SetCommands("mod", []string{}, "admin"); e != nil {
		t.Fatal(e)
	}
	result, e = s.Sync(input, false)
	if e != nil || len(result.Commands["mod"]) != 0 {
		t.Fatal("revocation not synchronized", result, e)
	}
}

func TestGameModePresenceAndExistingSchemaMigration(t *testing.T) {
	s := service(t)
	_, err := s.Sync(players.Sync{Server: "creative", Players: []players.Identity{{UUID: alice, Name: "Alice", GameMode: "CREATIVE"}}}, false)
	if err != nil {
		t.Fatal(err)
	}
	list, err := s.List("Alice", "", 1)
	if err != nil || list.Players[0].GameMode != "CREATIVE" {
		t.Fatal(list, err)
	}
	if _, err = s.DB.Exec("ALTER TABLE player_presence DROP COLUMN gamemode"); err != nil {
		t.Fatal(err)
	}
	if err = players.Migrate(s.DB); err != nil {
		t.Fatal(err)
	}
	s.DB.Exec("UPDATE player_presence SET seen=seen-1")
	_, err = s.Sync(players.Sync{Server: "survival", Players: []players.Identity{{UUID: alice, Name: "Alice", GameMode: "SURVIVAL"}}}, false)
	if err != nil {
		t.Fatal(err)
	}
	list, err = s.List("Alice", "", 1)
	if err != nil || list.Players[0].GameMode == "" {
		t.Fatal(list, err)
	}
}
