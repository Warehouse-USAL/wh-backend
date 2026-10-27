package auth

import (
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const secret = "dev-secret-change-in-prod-min-32-chars"

func sign(t *testing.T, method jwt.SigningMethod, key any, c Claims) string {
	t.Helper()
	s, err := jwt.NewWithClaims(method, c).SignedString(key)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func claims(sub, role string, exp time.Time) Claims {
	return Claims{
		RegisteredClaims: jwt.RegisteredClaims{Subject: sub, ExpiresAt: jwt.NewNumericDate(exp)},
		Email:            "a@b.c", Role: role,
	}
}

func TestVerifyAcceptsEveryHMACJJWTMayPick(t *testing.T) {
	v := NewVerifier(secret)
	for _, m := range []jwt.SigningMethod{jwt.SigningMethodHS256, jwt.SigningMethodHS384, jwt.SigningMethodHS512} {
		c, err := v.Verify(sign(t, m, []byte(secret), claims("u1", "OPERATOR", time.Now().Add(time.Hour))))
		if err != nil || c.UserID() != "u1" || c.Email != "a@b.c" || c.Role != "OPERATOR" {
			t.Errorf("%s: %+v %v", m.Alg(), c, err)
		}
	}
}

func TestVerifyRejects(t *testing.T) {
	v := NewVerifier(secret)
	good := claims("u1", "OPERATOR", time.Now().Add(time.Hour))
	noExp := Claims{RegisteredClaims: jwt.RegisteredClaims{Subject: "u1"}}
	cases := map[string]string{
		"wrong secret": sign(t, jwt.SigningMethodHS256, []byte("another-secret-another-secret-123"), good),
		"expired":      sign(t, jwt.SigningMethodHS256, []byte(secret), claims("u1", "", time.Now().Add(-time.Hour))),
		"no exp":       sign(t, jwt.SigningMethodHS256, []byte(secret), noExp),
		"no subject":   sign(t, jwt.SigningMethodHS256, []byte(secret), claims("", "", time.Now().Add(time.Hour))),
		"alg none":     sign(t, jwt.SigningMethodNone, jwt.UnsafeAllowNoneSignatureType, good),
		"garbage":      "not.a.jwt",
	}
	for name, tok := range cases {
		if _, err := v.Verify(tok); err != ErrInvalidToken {
			t.Errorf("%s: err = %v", name, err)
		}
	}
}

func TestIsSystemAdmin(t *testing.T) {
	for role, want := range map[string]bool{"SUPERADMIN": true, "ADMIN_SYSTEM": true, "PROVIDER": false, "OPERATOR": false, "DISPATCHER": false, "": false} {
		if got := (Claims{Role: role}).IsSystemAdmin(); got != want {
			t.Errorf("%q = %v", role, got)
		}
	}
}

func TestBearerToken(t *testing.T) {
	for in, want := range map[string]string{"Bearer abc": "abc", "bearer abc": "abc", "Bearer  abc ": "abc"} {
		if got, ok := BearerToken(in); !ok || got != want {
			t.Errorf("%q = %q, %v", in, got, ok)
		}
	}
	for _, in := range []string{"", "Bearer", "Bearer ", "Basic abc", "abc"} {
		if _, ok := BearerToken(in); ok {
			t.Errorf("%q should be rejected", in)
		}
	}
}
