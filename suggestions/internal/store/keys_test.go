package store

import "testing"

func TestKeys(t *testing.T) {
	if HotGlobal != "hot:global" || UserSKUs("u1") != "user:u1:skus" ||
		Cooccur("A") != "cooccur:A" || Buyers("A") != "sku:A:buyers" ||
		Processed("o1") != "processed:o1" {
		t.Fatal("key layout changed; docs/SUGGESTIONS.md documents it")
	}
}
