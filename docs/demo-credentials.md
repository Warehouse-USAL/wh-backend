# Demo data & credentials

The backend can seed a complete, interconnected "production-looking" dataset for demos and for
consumer apps to develop against.

## Enabling

Set `SEED_DEMO=true` and boot against a **fresh** database (one with no products yet):

```bash
SEED_DEMO=true make up-dev
```

- **Off by default.** It never runs unless `SEED_DEMO=true`.
- **Idempotent.** If any products already exist it no-ops, so restarts are safe and it can't
  overwrite an existing database.
- **Quiet.** Seeding writes directly to MongoDB and fires no Kafka events.

To re-seed, reset the database first (`make down` and delete `data/`, or drop the Mongo database)
and boot again with the flag set.

## What gets seeded

| Collection | Count | Notes |
|---|---|---|
| Users | 12 + admin | One per role (plus extra operators/providers); the SUPERADMIN comes from `ADMIN_EMAIL`/`ADMIN_PASSWORD` |
| Products | 24 | 6 per category (`TECNOLOGIA`, `HERRAMIENTAS`, `ALIMENTOS`, `OTROS`); one is intentionally low-stock |
| Warehouse | 2 zones / 7 lines / 35 positions | Zones B and C (Zone A is the baseline); most positions hold stock |
| Vehicles | 6 | 2 IDLE, 2 BUSY (linked to the in-progress orders), 1 OFFLINE, 1 ERROR |
| Orders | 25 | 7 PENDING, 2 IN_PROGRESS, 13 COMPLETED, 3 CANCELLED — stock reflects this history |

Stock is causally consistent: completed orders have drawn position stock down (FIFO), and
pending/in-progress orders are counted as *reserved*, so the product API's
`available − reserved` reads correctly.

## Credentials

All demo accounts share the password **`Demo1234!`** and are active.

| Email | Role | Name |
|---|---|---|
| `admin@smartwarehouse.local` | SUPERADMIN | System Admin *(password = `ADMIN_PASSWORD`)* |
| `system@smartwarehouse.local` | ADMIN_SYSTEM | Sofía Martínez |
| `warehouse@smartwarehouse.local` | ADMIN_WAREHOUSE | Diego Fernández |
| `sales@smartwarehouse.local` | ADMIN_SALES | Valentina Rodríguez |
| `provider1@smartwarehouse.local` | PROVIDER | Distribuidora del Sur S.A. |
| `provider2@smartwarehouse.local` | PROVIDER | Importadora Andina Ltda. |
| `dispatcher1@smartwarehouse.local` | DISPATCHER | Lucas Pérez |
| `dispatcher2@smartwarehouse.local` | DISPATCHER | Martín Gómez |
| `operator1@smartwarehouse.local` | OPERATOR | Camila Suárez |
| `operator2@smartwarehouse.local` | OPERATOR | Bruno Castro |
| `operator3@smartwarehouse.local` | OPERATOR | Lucía Méndez |
| `operator4@smartwarehouse.local` | OPERATOR | Joaquín Silva |
| `operator5@smartwarehouse.local` | OPERATOR | Florencia Díaz |

> **SUPERADMIN:** created by `DataInitializer` from `ADMIN_EMAIL` / `ADMIN_PASSWORD`. For a
> consistent demo set, run with `ADMIN_EMAIL=admin@smartwarehouse.local` and
> `ADMIN_PASSWORD=Demo1234!`.

## Logging in

```bash
curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"warehouse@smartwarehouse.local","password":"Demo1234!"}'
```

Returns `{ "token": "...", "user": { ... } }`. Send the token as `Authorization: Bearer <token>`
on subsequent requests.
