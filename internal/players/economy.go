package players

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"sort"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/garder500/minecraft-bungee/internal/control"
)

func economyMigrate(db *sql.DB) error {
	_, err := db.Exec(`CREATE TABLE IF NOT EXISTS wallets(uuid TEXT PRIMARY KEY,balance INTEGER NOT NULL DEFAULT 0 CHECK(balance>=0));
 CREATE TABLE IF NOT EXISTS event_runs(id TEXT PRIMARY KEY,payload TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS event_results(event_id TEXT NOT NULL,uuid TEXT NOT NULL,event TEXT NOT NULL,server TEXT NOT NULL,at INTEGER NOT NULL,result TEXT NOT NULL,gain INTEGER NOT NULL,PRIMARY KEY(event_id,uuid));
 CREATE INDEX IF NOT EXISTS event_history ON event_results(uuid,at DESC);
 CREATE TABLE IF NOT EXISTS money_operations(id TEXT PRIMARY KEY,uuid TEXT NOT NULL,actor TEXT NOT NULL,server TEXT NOT NULL,operation TEXT NOT NULL,amount INTEGER NOT NULL,at INTEGER NOT NULL);`)
	return err
}

type EventEntry struct {
	UUID   string `json:"uuid"`
	Result string `json:"result"`
	Gain   int64  `json:"gain"`
}
type EventResult struct {
	ID      string       `json:"id"`
	Event   string       `json:"event"`
	Server  string       `json:"server"`
	At      int64        `json:"at"`
	Entries []EventEntry `json:"entries"`
}

func validServer(s string) bool { return s == "creative" || s == "survival" || s == "lobby" }
func (s *Service) RecordEvent(in EventResult) error {
	if !ValidUUID(in.ID) || !validServer(in.Server) || len(in.Event) < 1 || !utf8.ValidString(in.Event) || utf8.RuneCountInString(in.Event) > 80 || strings.IndexFunc(in.Event, unicode.IsControl) >= 0 || in.At <= 0 || len(in.Entries) > 45 {
		return fmt.Errorf("invalid event")
	}
	seen := map[string]bool{}
	for _, e := range in.Entries {
		validResult := false
		switch e.Result {
		case "WIN", "LOSS", "DRAW", "COMPLETED", "ABANDONED", "STOPPED", "INTERRUPTED", "PRACTICE":
			validResult = true
		}
		if !ValidUUID(e.UUID) || seen[e.UUID] || e.Gain < 0 || e.Gain > 200000 || !validResult {
			return fmt.Errorf("invalid entry")
		}
		seen[e.UUID] = true
		if (e.Result == "ABANDONED" || e.Result == "STOPPED" || e.Result == "INTERRUPTED" || e.Result == "PRACTICE") && e.Gain != 0 {
			return fmt.Errorf("ineligible reward")
		}
	}
	tx, err := s.DB.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	canonical := in
	canonical.Entries = append([]EventEntry(nil), in.Entries...)
	sort.Slice(canonical.Entries, func(i, j int) bool { return canonical.Entries[i].UUID < canonical.Entries[j].UUID })
	encoded, _ := json.Marshal(canonical)
	var previous string
	err = tx.QueryRow("SELECT payload FROM event_runs WHERE id=?", in.ID).Scan(&previous)
	if err == nil {
		if previous != string(encoded) {
			return fmt.Errorf("conflicting event retry")
		}
		return tx.Commit()
	}
	if err != sql.ErrNoRows {
		return err
	}
	if _, err = tx.Exec("INSERT INTO event_runs(id,payload) VALUES(?,?)", in.ID, string(encoded)); err != nil {
		return err
	}
	for _, e := range in.Entries {
		r, err := tx.Exec("INSERT INTO event_results(event_id,uuid,event,server,at,result,gain) VALUES(?,?,?,?,?,?,?) ON CONFLICT(event_id,uuid) DO NOTHING", in.ID, e.UUID, in.Event, in.Server, in.At, e.Result, e.Gain)
		if err != nil {
			return err
		}
		n, _ := r.RowsAffected()
		if n == 0 {
			var event, server, result string
			var at, gain int64
			if err = tx.QueryRow("SELECT event,server,at,result,gain FROM event_results WHERE event_id=? AND uuid=?", in.ID, e.UUID).Scan(&event, &server, &at, &result, &gain); err != nil {
				return err
			}
			if event != in.Event || server != in.Server || at != in.At || result != e.Result || gain != e.Gain {
				return fmt.Errorf("conflicting event retry")
			}
			continue
		}
		if _, err = tx.Exec("INSERT INTO wallets(uuid,balance) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET balance=balance+excluded.balance", e.UUID, e.Gain); err != nil {
			return err
		}
	}
	return tx.Commit()
}
func decodeEconomy(w http.ResponseWriter, r *http.Request, v any) bool {
	d := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10))
	d.DisallowUnknownFields()
	if d.Decode(v) != nil {
		control.JSON(w, 400, map[string]string{"error": "invalid payload"})
		return false
	}
	return true
}
func (s *Service) economyRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /bridge/events", func(w http.ResponseWriter, r *http.Request) {
		var in EventResult
		if !decodeEconomy(w, r, &in) {
			return
		}
		if err := s.RecordEvent(in); err != nil {
			control.JSON(w, 400, map[string]string{"error": err.Error()})
			return
		}
		control.JSON(w, 200, map[string]bool{"ok": true})
	})
	mux.HandleFunc("POST /bridge/profile", func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			UUID string `json:"uuid"`
			Page int    `json:"page"`
		}
		if !decodeEconomy(w, r, &in) {
			return
		}
		if !ValidUUID(in.UUID) || in.Page < 0 || in.Page > 100000 {
			control.JSON(w, 400, map[string]string{"error": "invalid profile"})
			return
		}
		tx, err := s.DB.Begin()
		if err != nil {
			control.JSON(w, 503, map[string]string{"error": "profile unavailable"})
			return
		}
		defer tx.Rollback()
		var balance int64
		err = tx.QueryRow("SELECT balance FROM wallets WHERE uuid=?", in.UUID).Scan(&balance)
		if err != nil && err != sql.ErrNoRows {
			control.JSON(w, 503, map[string]string{"error": "profile unavailable"})
			return
		}
		rows, err := tx.Query("SELECT event,server,at,result,gain FROM event_results WHERE uuid=? ORDER BY at DESC,event_id LIMIT 37 OFFSET ?", in.UUID, in.Page*36)
		if err != nil {
			control.JSON(w, 503, map[string]string{"error": "history unavailable"})
			return
		}
		defer rows.Close()
		history := []map[string]any{}
		for rows.Next() {
			var event, server, result string
			var at, gain int64
			if rows.Scan(&event, &server, &at, &result, &gain) != nil {
				control.JSON(w, 503, map[string]string{"error": "history unavailable"})
				return
			}
			history = append(history, map[string]any{"event": event, "server": server, "at": at, "result": result, "gain": gain})
		}
		if rows.Err() != nil {
			control.JSON(w, 503, map[string]string{"error": "history unavailable"})
			return
		}
		more := len(history) > 36
		if more {
			history = history[:36]
		}
		currency := os.Getenv("NETWORK_CURRENCY_NAME")
		if currency == "" {
			currency = "Shards"
		}
		control.JSON(w, 200, map[string]any{"balance": balance, "currency": currency, "history": history, "more": more})
	})
	mux.HandleFunc("POST /bridge/money", func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			ID        string `json:"id"`
			UUID      string `json:"uuid"`
			Actor     string `json:"actor"`
			Server    string `json:"server"`
			Operation string `json:"operation"`
			Amount    int64  `json:"amount"`
		}
		if !decodeEconomy(w, r, &in) {
			return
		}
		if !ValidUUID(in.ID) || !ValidUUID(in.UUID) || in.Actor == "" || len(in.Actor) > 64 || !validServer(in.Server) || in.Amount < 0 || in.Amount > 1000000000 || (in.Operation != "give" && in.Operation != "take" && in.Operation != "set") {
			control.JSON(w, 400, map[string]string{"error": "invalid money operation"})
			return
		}
		tx, err := s.DB.Begin()
		if err != nil {
			control.JSON(w, 503, map[string]string{"error": "database unavailable"})
			return
		}
		defer tx.Rollback()
		var exists int
		err = tx.QueryRow("SELECT COUNT(*) FROM money_operations WHERE id=?", in.ID).Scan(&exists)
		if err != nil {
			control.JSON(w, 503, map[string]string{"error": "database unavailable"})
			return
		}
		if exists > 0 {
			var uuid, actor, server, operation string
			var amount int64
			err = tx.QueryRow("SELECT uuid,actor,server,operation,amount FROM money_operations WHERE id=?", in.ID).Scan(&uuid, &actor, &server, &operation, &amount)
			if err != nil || uuid != in.UUID || actor != in.Actor || server != in.Server || operation != in.Operation || amount != in.Amount {
				control.JSON(w, 409, map[string]string{"error": "conflicting operation retry"})
				return
			}
			control.JSON(w, 200, map[string]bool{"ok": true})
			return
		}
		var balance int64
		err = tx.QueryRow("SELECT balance FROM wallets WHERE uuid=?", in.UUID).Scan(&balance)
		if err != nil && err != sql.ErrNoRows {
			control.JSON(w, 503, map[string]string{"error": "database unavailable"})
			return
		}
		switch in.Operation {
		case "give":
			balance += in.Amount
		case "take":
			balance -= in.Amount
		case "set":
			balance = in.Amount
		}
		if balance < 0 || balance > 9000000000000000 {
			control.JSON(w, 400, map[string]string{"error": "invalid resulting balance"})
			return
		}
		_, err = tx.Exec("INSERT INTO wallets(uuid,balance) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET balance=excluded.balance", in.UUID, balance)
		if err == nil {
			_, err = tx.Exec("INSERT INTO money_operations(id,uuid,actor,server,operation,amount,at) VALUES(?,?,?,?,?,?,?)", in.ID, in.UUID, in.Actor, in.Server, in.Operation, in.Amount, time.Now().UnixMilli())
		}
		if err == nil {
			err = tx.Commit()
		}
		if err != nil {
			control.JSON(w, 503, map[string]string{"error": "money update failed"})
			return
		}
		control.JSON(w, 200, map[string]bool{"ok": true})
	})
}
