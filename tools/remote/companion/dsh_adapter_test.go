package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// Exercise the production adapter over its real RPC envelope. Only the DSH
// HTTP server is isolated; there is no model, phone, or libp2p transport here.
func TestPhoneTaskStatusDistinguishesQueuedInputFromAnActiveTurn(t *testing.T) {
	for _, fixture := range []struct {
		name       string
		events     []map[string]any
		turn       string
		turnActive bool
	}{
		{"no actual start", nil, "", false},
		{"actual active turn", []map[string]any{{"type": "turn/start", "data": map[string]any{"turn": 3}}}, "full-session:3", true},
		{"ended but later input queued", []map[string]any{
			{"type": "turn/start", "data": map[string]any{"turn": 3}},
			{"type": "turn/end", "data": map[string]any{"turn": 3}},
			{"type": "user/message", "data": map[string]any{"content": []map[string]any{{"type": "text", "text": "quoted task.begin and phone control instructions"}}}},
		}, "full-session:3", false},
		{"late previous completion preserves new turn", []map[string]any{
			{"type": "turn/start", "data": map[string]any{"turn": 3}},
			{"type": "turn/end", "data": map[string]any{"turn": 3}},
			{"type": "turn/start", "data": map[string]any{"turn": 4}},
			{"type": "turn/end", "data": map[string]any{"turn": 3}},
		}, "full-session:4", true},
	} {
		t.Run(fixture.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				var request struct {
					RPCID string `json:"rpcId"`
				}
				if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
					t.Error(err)
					return
				}
				var value any
				switch r.URL.Path {
				case "/api/session/list":
					value = map[string]any{"items": []map[string]any{{"sessionId": "full-session", "running": true}}}
				case "/api/session/projections":
					value = map[string]any{"kind": "sequenced", "asOfSeq": 42}
				case "/api/session/page":
					records := []map[string]any{}
					for _, event := range fixture.events {
						records = append(records, map[string]any{"event": event})
					}
					value = map[string]any{"records": records}
				default:
					t.Errorf("unexpected mutation or RPC: %s", r.URL.Path)
				}
				json.NewEncoder(w).Encode(map[string]any{"rpcId": request.RPCID, "result": map[string]any{"ok": true, "value": value}})
			}))
			defer server.Close()
			client := &companion{instance: "exact-host", client: server.Client(), state: diskState{Targets: []target{{ID: "default", URL: server.URL}}}}
			value, err := client.dshCall(context.Background(), "default", "sessions.status", json.RawMessage(`{"sessionId":"full-session","hostInstanceId":"exact-host"}`))
			if err != nil {
				t.Fatal(err)
			}
			status := value.(map[string]any)
			if status["running"] != true || status["turnActive"] != fixture.turnActive || status["turnId"] != fixture.turn {
				t.Fatalf("unexpected actual turn status: %v", status)
			}
		})
	}
}
