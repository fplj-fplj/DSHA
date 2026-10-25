package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
)

func (c *companion) cancelSession(ctx context.Context, id string, args json.RawMessage) (any, error) {
	t, e := c.findTarget(id)
	if e != nil {
		return nil, e
	}
	req, e := http.NewRequestWithContext(ctx, "POST", t.URL+"/api/dsha-connect/session.cancel", bytes.NewReader(args))
	if e != nil {
		return nil, e
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Cookie", t.Cookie)
	res, e := c.client.Do(req)
	if e != nil {
		return nil, e
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		return nil, fmt.Errorf("DSH Connect cancel HTTP %d; stopped state unknown", res.StatusCode)
	}
	var result map[string]any
	if e = json.NewDecoder(io.LimitReader(res.Body, 65536)).Decode(&result); e != nil {
		return nil, e
	}
	return result, nil
}
