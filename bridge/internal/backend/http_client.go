package backend

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"

	"github.com/Warehouse-USAL/wh-backend/bridge/internal/classify"
	"github.com/Warehouse-USAL/wh-backend/bridge/internal/requestid"
)

const maxErrorBody = 512

type httpClient struct {
	baseURL string
	apiKey  string
	http    *http.Client
}

func (c *httpClient) Send(
	ctx context.Context,
	req classify.Request,
) error {
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+req.Path, bytes.NewReader(req.Body))
	if err != nil {
		return fmt.Errorf("build request: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("X-Api-Key", c.apiKey)
	if id := requestid.From(ctx); id != "" {
		httpReq.Header.Set("X-Request-Id", id)
	}
	resp, err := c.http.Do(httpReq)
	if err != nil {
		return fmt.Errorf("post %s: %w", req.Path, err)
	}
	defer resp.Body.Close()
	return judge(resp)
}

func judge(
	resp *http.Response,
) error {
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return nil
	}
	body, _ := io.ReadAll(io.LimitReader(resp.Body, maxErrorBody))
	if isRejection(resp.StatusCode) {
		return &RejectedError{Status: resp.StatusCode, Body: string(body)}
	}
	return fmt.Errorf("backend answered status %d: %s", resp.StatusCode, body)
}

func isRejection(
	status int,
) bool {
	if status == http.StatusRequestTimeout || status == http.StatusTooManyRequests {
		return false
	}
	return status >= 400 && status < 500
}
