// Package api exposes the HTTP surface of the service (docs/SUGGESTIONS.md, "API").
package api

import (
	"context"
	"log/slog"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/auth"
	"github.com/Warehouse-USAL/wh-backend/suggestions/internal/recommend"
)

// Deps are the collaborators of the HTTP layer.
type Deps struct {
	Recs     *recommend.Service
	Verifier *auth.Verifier
	// Ping checks Redis; ConsumerAlive checks the Kafka loop. Both back /healthz.
	Ping          func(ctx context.Context) error
	ConsumerAlive func() bool
	Log           *slog.Logger
}

const healthTimeout = 2 * time.Second

// NewRouter wires every route.
func NewRouter(d Deps) *gin.Engine {
	gin.SetMode(gin.ReleaseMode)
	r := gin.New()
	r.HandleMethodNotAllowed = true
	r.Use(gin.CustomRecovery(func(c *gin.Context, _ any) {
		fail(c, http.StatusInternalServerError, "internal_error", "internal error")
	}))
	r.NoRoute(func(c *gin.Context) { fail(c, http.StatusNotFound, "not_found", "no such route") })
	r.NoMethod(func(c *gin.Context) {
		fail(c, http.StatusMethodNotAllowed, "method_not_allowed", "method not allowed")
	})

	h := &handlers{Deps: d}
	r.GET("/healthz", h.healthz)

	v1 := r.Group("/v1/recommendations", h.authenticate)
	v1.GET("/cart", h.cartGet)
	v1.POST("/cart", h.cartPost)
	v1.GET("/user/:userId", h.user)
	v1.GET("/hot", h.hot)
	return r
}
