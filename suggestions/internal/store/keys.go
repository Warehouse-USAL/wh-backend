// Package store defines the Redis data model (docs/SUGGESTIONS.md, "Redis model").
package store

// HotGlobal is the sorted set of globally trending SKUs (sku -> decayed quantity).
const HotGlobal = "hot:global"

// UserSKUs is the sorted set of SKUs a user has ordered (sku -> total quantity).
func UserSKUs(userID string) string { return "user:" + userID + ":skus" }

// Cooccur is the sorted set of SKUs ordered together with sku (other sku -> orders in common).
func Cooccur(sku string) string { return "cooccur:" + sku }

// Buyers is the inverted index of UserSKUs: the users that ordered sku (userId -> total
// quantity). It is what makes finding "similar users" a few bounded reads instead of a scan of
// every user:*:skus key.
func Buyers(sku string) string { return "sku:" + sku + ":buyers" }

// Processed is the marker of an order already applied (value 1, expires after the dedupe TTL). The
// fixed "processed:" prefix keeps an attacker-chosen order id from reaching any other key.
func Processed(orderID string) string { return "processed:" + orderID }
