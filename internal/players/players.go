// Package players manages player roles and a finite set of explicitly assigned commands.
package players

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/garder500/minecraft-bungee/internal/control"
)

type Role struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Color    string `json:"color"`
	Prefix   string `json:"prefix"`
	Priority int    `json:"priority"`
}

var Catalog = []Role{
	{"owner", "owner", "#F4BED7", "owner", 100},
	{"catgirlfriend", "catgirlfriend_", "#CAA4ED", "catgirlfriend_", 80},
	{"catgundisc", "catgundisc_", "#D8ACDE", "catgundisc_", 60},
	{"streamer", "streamer", "#B59AF0", "streamer", 40},
	{"mod", "Mod", "#F46A9B", "Mod", 20},
	{"player", "Yuyu's Bat", "#D6C7BD", "Yuyu's Bat", 0},
}

func ValidRole(id string) bool {
	for _, r := range Catalog {
		if r.ID == id {
			return true
		}
	}
	return false
}

var uuidPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var namePattern = regexp.MustCompile(`^[A-Za-z0-9_]{1,16}$`)

func ValidUUID(id string) bool {
	return uuidPattern.MatchString(id) && id != "00000000-0000-0000-0000-000000000000"
}
func Migrate(db *sql.DB) error {
	if err := economyMigrate(db); err != nil {
		return err
	}
	_, err := db.Exec(`CREATE TABLE IF NOT EXISTS players(uuid TEXT PRIMARY KEY,name TEXT NOT NULL,role TEXT NOT NULL DEFAULT 'player',first_seen INTEGER NOT NULL,last_seen INTEGER NOT NULL);
 CREATE TABLE IF NOT EXISTS player_presence(server TEXT NOT NULL,uuid TEXT NOT NULL,seen INTEGER NOT NULL,gamemode TEXT NOT NULL DEFAULT '',PRIMARY KEY(server,uuid));
 CREATE TABLE IF NOT EXISTS player_bridges(server TEXT PRIMARY KEY,seen INTEGER NOT NULL);
 CREATE INDEX IF NOT EXISTS player_presence_seen ON player_presence(seen);
 CREATE TABLE IF NOT EXISTS role_commands(role TEXT NOT NULL,command TEXT NOT NULL,PRIMARY KEY(role,command));
 CREATE TABLE IF NOT EXISTS player_operators(server TEXT NOT NULL,uuid TEXT NOT NULL,PRIMARY KEY(server,uuid));
 CREATE INDEX IF NOT EXISTS players_name ON players(name COLLATE NOCASE);`)
	if err != nil {
		return err
	}
	rows, e := db.Query("PRAGMA table_info(player_presence)")
	if e != nil {
		return e
	}
	found := false
	for rows.Next() {
		var cid, notnull, pk int
		var name, kind string
		var value any
		if e = rows.Scan(&cid, &name, &kind, &notnull, &value, &pk); e != nil {
			rows.Close()
			return e
		}
		if name == "gamemode" {
			found = true
		}
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		return e
	}
	if !found {
		_, e = db.Exec("ALTER TABLE player_presence ADD COLUMN gamemode TEXT NOT NULL DEFAULT ''")
	}
	return e
}

type Identity struct {
	GameMode string `json:"gamemode,omitempty"`
	UUID     string `json:"uuid"`
	Name     string `json:"name"`
	LastSeen int64  `json:"lastSeen"`
}
type Sync struct {
	Server    string     `json:"server"`
	Players   []Identity `json:"players"`
	Operators []Identity `json:"operators,omitempty"`
}
type SyncResult struct {
	Commands    map[string][]string `json:"commands"`
	Roles       []Role              `json:"roles"`
	Assignments map[string]string   `json:"assignments"`
	Online      int                 `json:"online"`
}
type Player struct {
	GameMode  string   `json:"gamemode"`
	Operators []string `json:"operators"`
	UUID      string   `json:"uuid"`
	Name      string   `json:"name"`
	Role      string   `json:"role"`
	FirstSeen int64    `json:"firstSeen"`
	LastSeen  int64    `json:"lastSeen"`
	Server    string   `json:"server"`
	Online    bool     `json:"online"`
}
type Result struct {
	Players  []Player `json:"players"`
	Total    int      `json:"total"`
	Online   int      `json:"online"`
	Page     int      `json:"page"`
	PageSize int      `json:"pageSize"`
	Bridges  []Bridge `json:"bridges"`
}
type Bridge struct {
	Server    string `json:"server"`
	LastSeen  int64  `json:"lastSeen"`
	Connected bool   `json:"connected"`
}
type Service struct{ DB *sql.DB }

const PageSize = 50
const PresenceTTL = 25

func (s *Service) List(search, role string, page int) (Result, error) {
	out := Result{Players: []Player{}, Bridges: []Bridge{}, Page: page, PageSize: PageSize}
	now := time.Now().Unix()
	escaped := strings.NewReplacer("\\", "\\\\", "%", "\\%", "_", "\\_").Replace(search)
	pattern := "%" + escaped + "%"
	where := ` WHERE (name LIKE ? ESCAPE '\' OR uuid LIKE ? ESCAPE '\') AND (?='' OR role=?)`
	if err := s.DB.QueryRow("SELECT COUNT(*) FROM players"+where, pattern, pattern, role, role).Scan(&out.Total); err != nil {
		return out, err
	}
	if err := s.DB.QueryRow("SELECT COUNT(DISTINCT uuid) FROM player_presence WHERE seen>?", now-PresenceTTL).Scan(&out.Online); err != nil {
		return out, err
	}
	rows, err := s.DB.Query(`SELECT uuid,name,role,first_seen,last_seen,COALESCE((SELECT server FROM player_presence WHERE player_presence.uuid=players.uuid AND seen>? ORDER BY seen DESC,server LIMIT 1),''),COALESCE((SELECT gamemode FROM player_presence WHERE player_presence.uuid=players.uuid AND seen>? ORDER BY seen DESC,server LIMIT 1),'') FROM players`+where+` ORDER BY last_seen DESC,name COLLATE NOCASE LIMIT ? OFFSET ?`, now-PresenceTTL, now-PresenceTTL, pattern, pattern, role, role, PageSize, (page-1)*PageSize)
	if err != nil {
		return out, err
	}
	for rows.Next() {
		var p Player
		if err = rows.Scan(&p.UUID, &p.Name, &p.Role, &p.FirstSeen, &p.LastSeen, &p.Server, &p.GameMode); err != nil {
			rows.Close()
			return out, err
		}
		p.Online = p.Server != ""
		out.Players = append(out.Players, p)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return out, err
	}
	ops, err := s.DB.Query("SELECT uuid,server FROM player_operators ORDER BY server")
	if err != nil {
		return out, err
	}
	byID := map[string][]string{}
	for ops.Next() {
		var uuid, server string
		if err = ops.Scan(&uuid, &server); err != nil {
			ops.Close()
			return out, err
		}
		byID[uuid] = append(byID[uuid], server)
	}
	err = ops.Err()
	ops.Close()
	if err != nil {
		return out, err
	}
	for i := range out.Players {
		out.Players[i].Operators = byID[out.Players[i].UUID]
		if out.Players[i].Operators == nil {
			out.Players[i].Operators = []string{}
		}
	}
	rows, err = s.DB.Query("SELECT server,seen FROM player_bridges ORDER BY server")
	if err != nil {
		return out, err
	}
	defer rows.Close()
	for rows.Next() {
		var b Bridge
		if err = rows.Scan(&b.Server, &b.LastSeen); err != nil {
			return out, err
		}
		b.Connected = b.LastSeen > now-PresenceTTL
		out.Bridges = append(out.Bridges, b)
	}
	return out, rows.Err()
}
func (s *Service) Sync(input Sync, register bool) (SyncResult, error) {
	out := SyncResult{Roles: Catalog, Assignments: map[string]string{}}
	if input.Server != "lobby" && input.Server != "creative" && input.Server != "survival" {
		return out, fmt.Errorf("unknown server")
	}
	if len(input.Players) > 500 || len(input.Operators) > 500 {
		return out, fmt.Errorf("too many players")
	}
	seen := map[string]bool{}
	for _, p := range input.Players {
		if !ValidUUID(p.UUID) || !namePattern.MatchString(p.Name) || seen[p.UUID] {
			return out, fmt.Errorf("invalid player identity")
		}
		seen[p.UUID] = true
	}
	for _, operator := range input.Operators {
		if !ValidUUID(operator.UUID) {
			return out, fmt.Errorf("invalid operator identity")
		}
	}
	commands, e := s.RoleCommands()
	if e != nil {
		return out, e
	}
	out.Commands = commands
	now := time.Now().Unix()
	tx, err := s.DB.Begin()
	if err != nil {
		return out, err
	}
	defer tx.Rollback()
	if !register {
		if _, err = tx.Exec("DELETE FROM player_operators WHERE server=?", input.Server); err != nil {
			return out, err
		}
		for _, operator := range input.Operators {
			if _, err = tx.Exec("INSERT OR IGNORE INTO player_operators(server,uuid) VALUES(?,?)", input.Server, operator.UUID); err != nil {
				return out, err
			}
		}
		if _, err = tx.Exec("DELETE FROM player_presence WHERE server=? OR seen<=?", input.Server, now-PresenceTTL); err != nil {
			return out, err
		}
		if _, err = tx.Exec("INSERT INTO player_bridges(server,seen) VALUES(?,?) ON CONFLICT(server) DO UPDATE SET seen=excluded.seen", input.Server, now); err != nil {
			return out, err
		}
	}
	for _, p := range input.Players {
		last := now
		if register {
			last = p.LastSeen
			if last < 0 || last > now {
				last = 0
			}
		}
		// Historical caches from another world must not revert a player's newer name.
		_, err = tx.Exec(`INSERT INTO players(uuid,name,first_seen,last_seen) VALUES(?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=CASE WHEN excluded.last_seen>=players.last_seen THEN excluded.name ELSE players.name END,last_seen=MAX(players.last_seen,excluded.last_seen)`, p.UUID, p.Name, last, last)
		if err != nil {
			return out, err
		}
		if !register {
			if _, err = tx.Exec("INSERT INTO player_presence(server,uuid,seen,gamemode) VALUES(?,?,?,?)", input.Server, p.UUID, now, p.GameMode); err != nil {
				return out, err
			}
		}
		var role string
		if err = tx.QueryRow("SELECT role FROM players WHERE uuid=?", p.UUID).Scan(&role); err != nil {
			return out, err
		}
		out.Assignments[p.UUID] = role
	}
	if err = tx.QueryRow("SELECT COUNT(DISTINCT uuid) FROM player_presence WHERE seen>?", now-PresenceTTL).Scan(&out.Online); err != nil {
		return out, err
	}
	return out, tx.Commit()
}
func (s *Service) SetRole(id, role, actor string) (bool, error) {
	if !ValidUUID(id) || !ValidRole(role) {
		return false, fmt.Errorf("invalid cosmetic role assignment")
	}
	tx, err := s.DB.Begin()
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	var before string
	err = tx.QueryRow("SELECT role FROM players WHERE uuid=?", id).Scan(&before)
	if err == sql.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if _, err = tx.Exec("UPDATE players SET role=? WHERE uuid=?", role, id); err != nil {
		return false, err
	}
	// The player UUID, old role, and new role are stored as structured JSON, never a command.
	detail, _ := json.Marshal(map[string]string{"uuid": id, "before": before, "after": role})
	if _, err = tx.Exec("INSERT INTO audit(at,username,server,action,result) VALUES(?,?,?,?,?)", time.Now().UTC().Format(time.RFC3339), actor, "network", "cosmetic-role:"+string(detail), 200); err != nil {
		return false, err
	}
	return true, tx.Commit()
}
func (s *Service) BridgeHandler(token string) http.Handler {
	mux := http.NewServeMux()
	s.economyRoutes(mux)
	for _, route := range []string{"sync", "register"} {
		register := route == "register"
		mux.HandleFunc("POST /bridge/"+route, func(w http.ResponseWriter, r *http.Request) {
			var in Sync
			decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 128<<10))
			decoder.DisallowUnknownFields()
			if decoder.Decode(&in) != nil {
				control.JSON(w, 400, map[string]string{"error": "invalid synchronization payload"})
				return
			}
			result, err := s.Sync(in, register)
			if err != nil {
				control.JSON(w, 400, map[string]string{"error": "synchronization failed"})
				return
			}
			control.JSON(w, 200, result)
		})
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		if !control.Equal(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "), token) {
			control.JSON(w, 401, map[string]string{"error": "unauthorized"})
			return
		}
		mux.ServeHTTP(w, r)
	})
}
