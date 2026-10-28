package api

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"

	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/auth"
	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/recommend"
)

const (
	defaultLimit    = 5
	defaultHotLimit = 10
	maxLimit        = 50
	maxCartSKUs     = 100
	maxSKULen       = 64
	maxBodyBytes    = 64 << 10
	claimsKey       = "claims"
)

type handlers struct{ Deps }

type errorBody struct {
	Error   string `json:"error"`
	Message string `json:"message"`
}

// fail writes the one error shape of the service: {"error": code, "message": text}.
func fail(c *gin.Context, status int, code, msg string) {
	c.AbortWithStatusJSON(status, errorBody{Error: code, Message: msg})
}

type response struct {
	Recommendations []recommend.Recommendation `json:"recommendations"`
}

func (h *handlers) respond(c *gin.Context, recs []recommend.Recommendation, err error) {
	if err != nil {
		h.Log.Error("reading recommendations", "path", c.FullPath(), "error", err)
		fail(c, http.StatusInternalServerError, "internal_error", "could not compute recommendations")
		return
	}
	if recs == nil {
		recs = []recommend.Recommendation{}
	}
	c.JSON(http.StatusOK, response{Recommendations: recs})
}

// authenticate requires a valid bearer token and stores its claims.
func (h *handlers) authenticate(c *gin.Context) {
	token, ok := auth.BearerToken(c.GetHeader("Authorization"))
	if !ok {
		fail(c, http.StatusUnauthorized, "unauthorized", "missing bearer token")
		return
	}
	claims, err := h.Verifier.Verify(token)
	if err != nil {
		fail(c, http.StatusUnauthorized, "unauthorized", "invalid or expired token")
		return
	}
	c.Set(claimsKey, claims)
	c.Next()
}

// checkLimit parses a limit, applying def when empty, and enforces 1..maxLimit.
func checkLimit(raw string, def int) (int, error) {
	if raw == "" {
		return def, nil
	}
	n, err := strconv.Atoi(raw)
	if err != nil || n < 1 || n > maxLimit {
		return 0, errors.New("limit must be an integer between 1 and " + strconv.Itoa(maxLimit))
	}
	return n, nil
}

func (h *handlers) hot(c *gin.Context) {
	limit, err := checkLimit(c.Query("limit"), defaultHotLimit)
	if err != nil {
		fail(c, http.StatusBadRequest, "bad_request", err.Error())
		return
	}
	recs, err := h.Recs.Hot(c.Request.Context(), limit)
	h.respond(c, recs, err)
}

func (h *handlers) user(c *gin.Context) {
	claims := c.MustGet(claimsKey).(auth.Claims)
	userID := c.Param("userId")
	if claims.UserID() != userID && !claims.IsSystemAdmin() {
		fail(c, http.StatusForbidden, "forbidden", "not allowed to read another user's recommendations")
		return
	}
	limit, err := checkLimit(c.Query("limit"), defaultLimit)
	if err != nil {
		fail(c, http.StatusBadRequest, "bad_request", err.Error())
		return
	}
	recs, err := h.Recs.User(c.Request.Context(), userID, limit)
	h.respond(c, recs, err)
}

// cartGet: GET /cart?skus=A,B&limit=5 (skus may also repeat: ?skus=A&skus=B).
func (h *handlers) cartGet(c *gin.Context) {
	var raw []string
	for _, v := range c.QueryArray("skus") {
		raw = append(raw, strings.Split(v, ",")...)
	}
	limit, err := checkLimit(c.Query("limit"), defaultLimit)
	if err != nil {
		fail(c, http.StatusBadRequest, "bad_request", err.Error())
		return
	}
	h.cart(c, raw, limit)
}

// cartPost: POST /cart {"skus":[...],"limit":5}, for lists too long for a URL.
func (h *handlers) cartPost(c *gin.Context) {
	var body struct {
		SKUs  []string `json:"skus"`
		Limit *int     `json:"limit"`
	}
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxBodyBytes)
	if err := c.ShouldBindJSON(&body); err != nil {
		fail(c, http.StatusBadRequest, "bad_request", "body must be JSON like {\"skus\":[\"A\"],\"limit\":5}")
		return
	}
	limit := defaultLimit
	if body.Limit != nil {
		var err error
		if limit, err = checkLimit(strconv.Itoa(*body.Limit), defaultLimit); err != nil {
			fail(c, http.StatusBadRequest, "bad_request", err.Error())
			return
		}
	}
	h.cart(c, body.SKUs, limit)
}

// cart is the logic shared by both cart routes.
func (h *handlers) cart(c *gin.Context, raw []string, limit int) {
	skus, err := normaliseSKUs(raw)
	if err != nil {
		fail(c, http.StatusBadRequest, "bad_request", err.Error())
		return
	}
	recs, err := h.Recs.Cart(c.Request.Context(), skus, limit)
	h.respond(c, recs, err)
}

// normaliseSKUs trims, drops blanks and duplicates, and enforces the size bounds.
func normaliseSKUs(raw []string) ([]string, error) {
	seen := make(map[string]bool, len(raw))
	out := make([]string, 0, len(raw))
	for _, s := range raw {
		s = strings.TrimSpace(s)
		if s == "" || seen[s] {
			continue
		}
		if len(s) > maxSKULen {
			return nil, errors.New("sku longer than " + strconv.Itoa(maxSKULen) + " characters")
		}
		seen[s] = true
		out = append(out, s)
	}
	if len(out) == 0 {
		return nil, errors.New("skus must contain at least one sku")
	}
	if len(out) > maxCartSKUs {
		return nil, errors.New("at most " + strconv.Itoa(maxCartSKUs) + " skus")
	}
	return out, nil
}

func (h *handlers) healthz(c *gin.Context) {
	ctx, cancel := context.WithTimeout(c.Request.Context(), healthTimeout)
	defer cancel()
	redisOK, consumerOK := h.Ping(ctx) == nil, h.ConsumerAlive()
	status := http.StatusOK
	if !redisOK || !consumerOK {
		status = http.StatusServiceUnavailable
	}
	c.JSON(status, gin.H{"status": map[bool]string{true: "ok", false: "unavailable"}[redisOK && consumerOK],
		"redis": okStr(redisOK), "consumer": okStr(consumerOK)})
}

func okStr(ok bool) string {
	if ok {
		return "ok"
	}
	return "down"
}
