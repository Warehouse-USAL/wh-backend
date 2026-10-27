package api

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/golang-jwt/jwt/v5"
	"github.com/redis/go-redis/v9"

	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/auth"
	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/ingest"
	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/recommend"
)

const secret = "dev-secret-change-in-prod-min-32-chars"

type env struct {
	mr       *miniredis.Miniredis
	rdb      *redis.Client
	router   http.Handler
	consumer bool
}

func newEnv(t *testing.T) *env {
	t.Helper()
	e := &env{consumer: true}
	e.mr = miniredis.RunT(t)
	e.rdb = redis.NewClient(&redis.Options{Addr: e.mr.Addr(), MaxRetries: -1})
	t.Cleanup(func() { _ = e.rdb.Close() })
	e.router = NewRouter(Deps{
		Recs:          recommend.New(e.rdb, recommend.Options{}),
		Verifier:      auth.NewVerifier(secret),
		Ping:          func(ctx context.Context) error { return e.rdb.Ping(ctx).Err() },
		ConsumerAlive: func() bool { return e.consumer },
		Log:           slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	// u1 and u2 share A and B; u2 also bought X and Y; A and B co-occur.
	for _, o := range []struct {
		user string
		skus []string
	}{{"u1", []string{"A", "B"}}, {"u2", []string{"A", "B", "X"}}, {"u2", []string{"Y"}}} {
		ev := ingest.OrderCompleted{UserID: o.user}
		for _, s := range o.skus {
			ev.Items = append(ev.Items, ingest.Item{SKU: s, Quantity: 1})
		}
		if err := ingest.Apply(context.Background(), e.rdb, ev); err != nil {
			t.Fatal(err)
		}
	}
	return e
}

func token(t *testing.T, sub, role string) string {
	t.Helper()
	s, err := jwt.NewWithClaims(jwt.SigningMethodHS256, auth.Claims{
		RegisteredClaims: jwt.RegisteredClaims{Subject: sub, ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Hour))},
		Email:            sub + "@x.y", Role: role,
	}).SignedString([]byte(secret))
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func (e *env) do(method, path, tok, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	if tok != "" {
		req.Header.Set("Authorization", "Bearer "+tok)
	}
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	w := httptest.NewRecorder()
	e.router.ServeHTTP(w, req)
	return w
}

func skus(t *testing.T, w *httptest.ResponseRecorder) []string {
	t.Helper()
	if w.Code != http.StatusOK {
		t.Fatalf("status %d: %s", w.Code, w.Body)
	}
	var r struct {
		Recommendations []struct {
			SKU   string  `json:"sku"`
			Score float64 `json:"score"`
		} `json:"recommendations"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &r); err != nil || r.Recommendations == nil {
		t.Fatalf("bad body %s: %v", w.Body, err)
	}
	out := []string{}
	for _, x := range r.Recommendations {
		out = append(out, x.SKU)
	}
	return out
}

func wantError(t *testing.T, w *httptest.ResponseRecorder, status int, code string) {
	t.Helper()
	var b errorBody
	if w.Code != status || json.Unmarshal(w.Body.Bytes(), &b) != nil || b.Error != code || b.Message == "" {
		t.Fatalf("want %d/%s, got %d %s", status, code, w.Code, w.Body)
	}
}

func join(s []string) string { return strings.Join(s, ",") }

func TestAuthRequiredOnEveryRecommendationRoute(t *testing.T) {
	e := newEnv(t)
	for _, r := range [][2]string{
		{"GET", "/v1/recommendations/hot"}, {"GET", "/v1/recommendations/user/u1"},
		{"GET", "/v1/recommendations/cart?skus=A"}, {"POST", "/v1/recommendations/cart"},
	} {
		wantError(t, e.do(r[0], r[1], "", ""), 401, "unauthorized")
		wantError(t, e.do(r[0], r[1], "garbage", ""), 401, "unauthorized")
	}
}

func TestHot(t *testing.T) {
	e := newEnv(t)
	tok := token(t, "anyone", "PROVIDER")
	if got := join(skus(t, e.do("GET", "/v1/recommendations/hot?limit=2", tok, ""))); got != "B,A" && got != "A,B" {
		t.Errorf("hot = %s", got)
	}
	if got := skus(t, e.do("GET", "/v1/recommendations/hot", tok, "")); len(got) != 4 {
		t.Errorf("default limit should return everything here: %v", got)
	}
}

func TestLimitValidation(t *testing.T) {
	e := newEnv(t)
	tok := token(t, "u1", "OPERATOR")
	for _, p := range []string{"/hot?limit=0", "/hot?limit=-1", "/hot?limit=abc", "/hot?limit=51",
		"/user/u1?limit=0", "/cart?skus=A&limit=99"} {
		wantError(t, e.do("GET", "/v1/recommendations"+p, tok, ""), 400, "bad_request")
	}
	if len(skus(t, e.do("GET", "/v1/recommendations/hot?limit=50", tok, ""))) == 0 {
		t.Error("limit=50 is allowed")
	}
}

func TestUserAuthorizationRule(t *testing.T) {
	e := newEnv(t)
	path := "/v1/recommendations/user/u1"
	if got := join(skus(t, e.do("GET", path, token(t, "u1", "OPERATOR"), ""))); got != "X,Y" {
		t.Errorf("own recs (similar users' purchases, not already bought) = %s", got)
	}
	for _, role := range []string{"SUPERADMIN", "ADMIN_SYSTEM"} {
		if got := join(skus(t, e.do("GET", path, token(t, "admin", role), ""))); got != "X,Y" {
			t.Errorf("%s = %s", role, got)
		}
	}
	for _, role := range []string{"OPERATOR", "DISPATCHER", "PROVIDER", ""} {
		wantError(t, e.do("GET", path, token(t, "u2", role), ""), 403, "forbidden")
	}
}

func TestUserColdStartFallsBackToHot(t *testing.T) {
	e := newEnv(t)
	got := skus(t, e.do("GET", "/v1/recommendations/user/new-user?limit=1", token(t, "new-user", "OPERATOR"), ""))
	if len(got) != 1 {
		t.Errorf("cold start = %v", got)
	}
}

func TestCartGetAndPostGiveTheSameAnswer(t *testing.T) {
	e := newEnv(t)
	tok := token(t, "u1", "OPERATOR")
	get := e.do("GET", "/v1/recommendations/cart?skus=A,%20A,&skus=B&limit=5", tok, "")
	post := e.do("POST", "/v1/recommendations/cart", tok, `{"skus":["A","B","A"],"limit":5}`)
	if get.Body.String() != post.Body.String() || join(skus(t, get)) != "X" {
		t.Errorf("get=%s post=%s", get.Body, post.Body)
	}
	if got := skus(t, e.do("POST", "/v1/recommendations/cart", tok, `{"skus":["A"]}`)); len(got) == 0 {
		t.Error("limit defaults to 5")
	}
}

func TestCartValidation(t *testing.T) {
	e := newEnv(t)
	tok := token(t, "u1", "OPERATOR")
	many := make([]string, 101)
	for i := range many {
		many[i] = "S" + string(rune('a'+i%26)) + strings.Repeat("x", i)
	}
	long := strings.Repeat("z", 65)
	for name, w := range map[string]*httptest.ResponseRecorder{
		"get no skus":     e.do("GET", "/v1/recommendations/cart", tok, ""),
		"get blank skus":  e.do("GET", "/v1/recommendations/cart?skus=,,", tok, ""),
		"get long sku":    e.do("GET", "/v1/recommendations/cart?skus="+long, tok, ""),
		"get too many":    e.do("GET", "/v1/recommendations/cart?skus="+join(many), tok, ""),
		"post empty":      e.do("POST", "/v1/recommendations/cart", tok, `{"skus":[]}`),
		"post not json":   e.do("POST", "/v1/recommendations/cart", tok, `nope`),
		"post no body":    e.do("POST", "/v1/recommendations/cart", tok, ``),
		"post bad limit":  e.do("POST", "/v1/recommendations/cart", tok, `{"skus":["A"],"limit":0}`),
		"post limit type": e.do("POST", "/v1/recommendations/cart", tok, `{"skus":["A"],"limit":"x"}`),
	} {
		if w.Code != 400 {
			t.Errorf("%s: %d %s", name, w.Code, w.Body)
		}
	}
}

func TestHealthz(t *testing.T) {
	e := newEnv(t)
	w := e.do("GET", "/healthz", "", "")
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"status":"ok"`) {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
	e.consumer = false
	w = e.do("GET", "/healthz", "", "")
	if w.Code != 503 || !strings.Contains(w.Body.String(), `"consumer":"down"`) || !strings.Contains(w.Body.String(), `"redis":"ok"`) {
		t.Fatalf("consumer down: %d %s", w.Code, w.Body)
	}
	e.consumer = true
	e.mr.Close()
	w = e.do("GET", "/healthz", "", "")
	if w.Code != 503 || !strings.Contains(w.Body.String(), `"redis":"down"`) {
		t.Fatalf("redis down: %d %s", w.Code, w.Body)
	}
}

func TestRedisFailureIs500(t *testing.T) {
	e := newEnv(t)
	e.mr.Close()
	wantError(t, e.do("GET", "/v1/recommendations/hot", token(t, "u1", "OPERATOR"), ""), 500, "internal_error")
}

func TestUnknownRouteAndMethodUseTheErrorShape(t *testing.T) {
	e := newEnv(t)
	wantError(t, e.do("GET", "/nope", "", ""), 404, "not_found")
	wantError(t, e.do("DELETE", "/healthz", "", ""), 405, "method_not_allowed")
}

func TestPanicIsContained(t *testing.T) {
	e := newEnv(t)
	e.router = NewRouter(Deps{Verifier: auth.NewVerifier(secret), Log: slog.New(slog.NewTextHandler(io.Discard, nil))})
	// Recs is nil: the handler panics, the recovery turns it into the standard 500.
	wantError(t, e.do("GET", "/v1/recommendations/hot", token(t, "u", "OPERATOR"), ""), 500, "internal_error")
}
