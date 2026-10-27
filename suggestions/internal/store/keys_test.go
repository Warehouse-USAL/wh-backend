package store

import "testing"

func TestKeys(t *testing.T) {
	if HotGlobal != "hot:global" || UserSKUs("u1") != "user:u1:skus" ||
		Cooccur("A") != "cooccur:A" || Buyers("A") != "sku:A:buyers" {
		t.Fatal("key layout changed; docs/SUGGESTIONS.md documents it")
	}
}
