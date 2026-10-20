package players_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"

	"github.com/garder500/minecraft-bungee/internal/players"
)

func TestEventCreditIsAtomicIdempotentAndRejectsConflictingRetries(t *testing.T) {
	s := service(t)
	event := players.EventResult{ID: "12345678-1234-4234-8234-000000000001", Event: "Village", Server: "creative", At: 1000, Entries: []players.EventEntry{{UUID: alice, Result: "WIN", Gain: 15}}}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := s.RecordEvent(event); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	var balance, count int
	if err := s.DB.QueryRow("SELECT balance FROM wallets WHERE uuid=?", alice).Scan(&balance); err != nil {
		t.Fatal(err)
	}
	s.DB.QueryRow("SELECT COUNT(*) FROM event_results").Scan(&count)
	if balance != 15 || count != 1 {
		t.Fatal(balance, count)
	}
	event.Entries[0].Gain = 100
	if s.RecordEvent(event) == nil {
		t.Fatal("conflicting retry accepted")
	}
	event.ID = "12345678-1234-4234-8234-000000000002"
	event.Entries = append(event.Entries, players.EventEntry{UUID: "invalid", Result: "WIN", Gain: 10})
	if s.RecordEvent(event) == nil {
		t.Fatal("invalid batch accepted")
	}
	s.DB.QueryRow("SELECT balance FROM wallets WHERE uuid=?", alice).Scan(&balance)
	if balance != 15 {
		t.Fatal("partially credited invalid batch")
	}
}
func economyRequest(s http.Handler, route, body, token string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("POST", "/bridge/"+route, strings.NewReader(body))
	r.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	return w
}
func TestProfileAcrossServersPaginationAndBridgeAuth(t *testing.T) {
	t.Setenv("NETWORK_CURRENCY_NAME", "Shards")
	s := service(t)
	handler := s.BridgeHandler("test-secret")
	for i := 0; i < 38; i++ {
		id := "12345678-1234-4234-8234-0000000000"
		const digits = "0123456789abcdef"
		id += string([]byte{digits[i/16], digits[i%16]})
		server := "creative"
		if i%2 == 0 {
			server = "survival"
		}
		if err := s.RecordEvent(players.EventResult{ID: id, Event: "Event", Server: server, At: int64(i + 1), Entries: []players.EventEntry{{UUID: alice, Result: "COMPLETED", Gain: 10}}}); err != nil {
			t.Fatal(err)
		}
	}
	if w := economyRequest(handler, "profile", `{"uuid":"`+alice+`","page":0}`, "wrong"); w.Code != 401 {
		t.Fatal(w.Code)
	}
	for page, want := range []int{36, 2} {
		body, _ := json.Marshal(map[string]any{"uuid": alice, "page": page})
		w := economyRequest(handler, "profile", string(body), "test-secret")
		if w.Code != 200 {
			t.Fatal(w.Code, w.Body.String())
		}
		var result struct {
			Balance  int
			Currency string
			History  []any
			More     bool
		}
		json.Unmarshal(w.Body.Bytes(), &result)
		if result.Balance != 380 || result.Currency != "Shards" || len(result.History) != want || result.More != (page == 0) {
			t.Fatal(result)
		}
	}
}
func TestMoneyCorrectionsAreAuditedAndDoNotOverdraw(t *testing.T) {
	s := service(t)
	handler := s.BridgeHandler("test-secret")
	in := map[string]any{"id": "12345678-1234-4234-8234-000000000001", "uuid": alice, "actor": "console", "server": "lobby", "operation": "give", "amount": 30}
	send := func() int {
		body, _ := json.Marshal(in)
		return economyRequest(handler, "money", string(body), "test-secret").Code
	}
	if send() != 200 || send() != 200 {
		t.Fatal("correction/retry failed")
	}
	in["amount"] = 31
	if send() != 409 {
		t.Fatal("conflicting operation accepted")
	}
	var balance, count int
	s.DB.QueryRow("SELECT balance FROM wallets WHERE uuid=?", alice).Scan(&balance)
	s.DB.QueryRow("SELECT COUNT(*) FROM money_operations").Scan(&count)
	if balance != 30 || count != 1 {
		t.Fatal(balance, count)
	}
	in["id"] = "12345678-1234-4234-8234-000000000002"
	in["operation"] = "take"
	in["amount"] = 31
	if send() != 400 {
		t.Fatal("overdraft accepted")
	}
}
func TestIneligibleEventsCannotPay(t *testing.T) {
	s := service(t)
	for _, result := range []string{"PRACTICE", "STOPPED", "INTERRUPTED", "ABANDONED", "WIN|LOSS"} {
		event := players.EventResult{ID: "12345678-1234-4234-8234-000000000001", Event: "Test", Server: "creative", At: 1000, Entries: []players.EventEntry{{UUID: alice, Result: result, Gain: 10}}}
		if s.RecordEvent(event) == nil {
			t.Fatal("paid", result)
		}
	}
}

// Explicit opt-in fixture used by scripts/smoke-community.cjs in an isolated Docker network.
func TestCommunityBridgeFixture(t *testing.T) {
	if os.Getenv("COMMUNITY_BRIDGE_FIXTURE") != "1" {
		t.Skip("integration fixture only")
	}
	token := os.Getenv("COMMUNITY_BRIDGE_TOKEN")
	if len(token) < 32 {
		t.Fatal("fixture token missing")
	}
	s := service(t)
	server := &http.Server{Addr: ":8083", Handler: s.BridgeHandler(token)}
	t.Cleanup(func() { server.Close() })
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		t.Fatal(err)
	}
}

func TestUnicodeEventNames(t *testing.T) {
	s := service(t)
	event := players.EventResult{ID: "12345678-1234-4234-8234-000000000001", Event: strings.Repeat("é", 80), Server: "creative", At: 1000, Entries: []players.EventEntry{{UUID: alice, Result: "COMPLETED", Gain: 10}}}
	if err := s.RecordEvent(event); err != nil {
		t.Fatal(err)
	}
}

func TestUnavailableProfileNeverReturnsZeroBalance(t *testing.T) {
	s := service(t)
	handler := s.BridgeHandler("test-secret")
	s.DB.Close()
	body, _ := json.Marshal(map[string]any{"uuid": alice, "page": 0})
	w := economyRequest(handler, "profile", string(body), "test-secret")
	if w.Code != 503 || strings.Contains(w.Body.String(), "balance") {
		t.Fatal(w.Code, w.Body.String())
	}
}
