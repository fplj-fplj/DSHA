package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

func (c *companion) dshCall(ctx context.Context, id, method string, args json.RawMessage) (any, error) {
	var common map[string]json.RawMessage
	_ = json.Unmarshal(args, &common)
	if raw, ok := common["hostInstanceId"]; ok {
		var instance string
		_ = json.Unmarshal(raw, &instance)
		if instance != c.instance {
			return nil, errors.New("stale DSH host instance")
		}
	}
	if method == "sessions.create" {
		filtered := map[string]json.RawMessage{}
		for _, key := range []string{"cwd", "sessionId", "workspaceId", "agentPreset"} {
			if value, ok := common[key]; ok {
				filtered[key] = value
			}
		}
		args, _ = json.Marshal(filtered)
	}
	if method == "sessions.send" || method == "sessions.cancel" {
		var in map[string]json.RawMessage
		if json.Unmarshal(args, &in) != nil {
			return nil, errors.New("invalid session request")
		}
		if raw, ok := in["hostInstanceId"]; ok {
			var instance string
			_ = json.Unmarshal(raw, &instance)
			if instance != c.instance {
				return nil, errors.New("stale DSH host instance")
			}
		}
		out := map[string]any{}
		var sessionID string
		_ = json.Unmarshal(in["sessionId"], &sessionID)
		if sessionID == "" {
			return nil, errors.New("sessionId required")
		}
		out["sessionId"] = sessionID
		if method == "sessions.send" {
			var requestID, text string
			_ = json.Unmarshal(in["requestId"], &requestID)
			_ = json.Unmarshal(in["text"], &text)
			if requestID == "" {
				return nil, errors.New("requestId required; unknown submissions must not be replayed")
			}
			out["requestId"] = requestID
			out["mode"] = "queue"
			if len(in["content"]) > 0 {
				out["content"] = in["content"]
			} else if strings.TrimSpace(text) != "" {
				out["content"] = []map[string]string{{"type": "text", "text": text}}
			} else {
				return nil, errors.New("text or content required")
			}
			if value, ok := in["clientTimeZone"]; ok {
				out["clientTimeZone"] = value
			}
		}
		args, _ = json.Marshal(out)
	}
	endpoints := map[string]string{"sessions.list": "session/list", "sessions.create": "session/create", "sessions.send": "session/prompt", "sessions.cancel": "session/cancel"}
	if method == "sessions.cancel" {
		return c.cancelSession(ctx, id, args)
	}
	if endpoint, ok := endpoints[method]; ok {
		return c.rpcCall(ctx, id, endpoint, args)
	}
	if method != "sessions.status" {
		return nil, errors.New("unsupported DSH method")
	}
	var request struct {
		SessionID string `json:"sessionId"`
	}
	if json.Unmarshal(args, &request) != nil || request.SessionID == "" {
		return nil, errors.New("sessionId required")
	}
	args, _ = json.Marshal(map[string]string{"sessionId": request.SessionID})
	list, e := c.rpcCall(ctx, id, "session/list", json.RawMessage(`{}`))
	if e != nil {
		return nil, e
	}
	var rows struct {
		Items []struct {
			SessionID string `json:"sessionId"`
			Running   bool   `json:"running"`
		} `json:"items"`
	}
	if e = json.Unmarshal(list, &rows); e != nil {
		return nil, e
	}
	found, running := false, false
	for _, row := range rows.Items {
		if row.SessionID == request.SessionID {
			found = true
			running = row.Running
			break
		}
	}
	if !found {
		return nil, errors.New("session not found")
	}
	projections, e := c.rpcCall(ctx, id, "session/projections", args)
	if e != nil {
		return nil, e
	}
	var p struct {
		AsOfSeq int64  `json:"asOfSeq"`
		Kind    string `json:"kind"`
	}
	if e = json.Unmarshal(projections, &p); e != nil {
		return nil, e
	}
	result := map[string]any{"sessionId": request.SessionID, "taskId": request.SessionID, "turnId": "", "turnActive": false, "running": running, "text": "", "hostInstanceId": c.instance, "projections": projections}
	if p.Kind != "sequenced" {
		return result, nil
	}
	pageArgs, _ := json.Marshal(map[string]any{"address": map[string]any{"kind": "session", "sessionId": request.SessionID}, "throughSeq": p.AsOfSeq, "maxMessages": 8})
	page, e := c.rpcCall(ctx, id, "session/page", pageArgs)
	if e != nil {
		return nil, e
	}
	var history struct {
		Records []struct {
			Event struct {
				Type string          `json:"type"`
				Data json.RawMessage `json:"data"`
				Seq  int64           `json:"seq"`
			} `json:"event"`
		} `json:"records"`
	}
	if e = json.Unmarshal(page, &history); e != nil {
		return nil, e
	}
	var texts []string
	for _, record := range history.Records {
		event := record.Event
		var data map[string]any
		_ = json.Unmarshal(event.Data, &data)
		if event.Type == "turn/start" {
			if turn, ok := data["turn"].(float64); ok {
				result["turnId"] = fmt.Sprintf("%s:%.0f", request.SessionID, turn)
				result["turnActive"] = true
			}
		}
		if event.Type == "turn/end" {
			if turn, ok := data["turn"].(float64); ok && result["turnId"] == fmt.Sprintf("%s:%.0f", request.SessionID, turn) {
				result["turnActive"] = false
			}
		}
		if event.Type == "user/message" || event.Type == "assistant/message" {
			extractMessageText(data, &texts)
		}
	}
	result["text"] = strings.Join(texts, "\n")
	result["history"] = json.RawMessage(page)
	return result, nil
}
func extractMessageText(data map[string]any, texts *[]string) {
	if message, ok := data["message"].(map[string]any); ok {
		data = message
	}
	if content, ok := data["content"].([]any); ok {
		for _, raw := range content {
			if block, ok := raw.(map[string]any); ok && block["type"] == "text" {
				if text, ok := block["text"].(string); ok {
					*texts = append(*texts, text)
				}
			}
		}
	}
}
