// Package auth verifies the JWTs wh-backend issues, locally, with the shared secret.
package auth

import (
	"errors"
	"strings"

	"github.com/golang-jwt/jwt/v5"
)

// Roles allowed to read any user's recommendations (backend EntityRegistry SYSTEM_ADMINS).
const (
	RoleSuperAdmin = "SUPERADMIN"
	RoleAdminSys   = "ADMIN_SYSTEM"
)

// Claims are the claims wh-backend's JwtService puts in a token.
type Claims struct {
	jwt.RegisteredClaims
	Email string `json:"email"`
	Role  string `json:"role"`
}

// UserID is the token subject.
func (c Claims) UserID() string { return c.Subject }

// IsSystemAdmin reports whether the role may read other users' data.
func (c Claims) IsSystemAdmin() bool { return c.Role == RoleSuperAdmin || c.Role == RoleAdminSys }

// ErrInvalidToken covers every reason a token is rejected.
var ErrInvalidToken = errors.New("invalid token")

// Verifier validates bearer tokens.
type Verifier struct {
	secret []byte
	parser *jwt.Parser
}

// NewVerifier builds a Verifier for the shared secret. The backend signs with jjwt, which picks
// HS256, HS384 or HS512 from the secret's length, so every HMAC algorithm is accepted and
// nothing else (never "none", never RSA/ECDSA with the secret as a public key).
func NewVerifier(secret string) *Verifier {
	return &Verifier{
		secret: []byte(secret),
		parser: jwt.NewParser(
			jwt.WithValidMethods([]string{"HS256", "HS384", "HS512"}),
			jwt.WithExpirationRequired(),
		),
	}
}

// Verify checks the signature and expiry of a token and returns its claims.
func (v *Verifier) Verify(token string) (Claims, error) {
	var c Claims
	t, err := v.parser.ParseWithClaims(token, &c, func(*jwt.Token) (any, error) { return v.secret, nil })
	if err != nil || !t.Valid || c.Subject == "" {
		return Claims{}, ErrInvalidToken
	}
	return c, nil
}

// BearerToken extracts the token from an Authorization header value.
func BearerToken(header string) (string, bool) {
	scheme, token, ok := strings.Cut(header, " ")
	token = strings.TrimSpace(token)
	if !ok || !strings.EqualFold(scheme, "Bearer") || token == "" {
		return "", false
	}
	return token, true
}
