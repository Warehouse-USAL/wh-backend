# Backend console

SUPERADMIN-only web console for the backend: a Vite + React + TypeScript SPA (coss ui, Tailwind v4,
Geist fonts), served by nginx at `/console/`. It calls the backend same-origin under `/api`; nginx (and the
Vite dev server) strip the prefix before forwarding.

## Scripts

| Command | What it does |
| --- | --- |
| `npm run dev` | Dev server at http://localhost:5173/console/, proxying `/api` to `http://localhost:8080` |
| `npm run lint` | ESLint |
| `npm run typecheck` | `tsc -b` |
| `npm test` | Vitest; `npm test -- --coverage` enforces 90% and writes `coverage/` (incl. Cobertura for CI) |
| `npm run build` | Type-check and build to `dist/` |

Node version: `.nvmrc`.

## Running against a local backend

Start the backend on port 8080 (see the root README), then `npm run dev` and sign in with a SUPERADMIN account.
Any other role is refused by the console after login. To point the dev proxy elsewhere, change
`server.proxy` in `vite.config.ts`.

With Docker: `docker compose up -d console` serves it at http://localhost:8083/console/. The dev compose also
mounts `nginx.dev-tools.conf`, which exposes frame-safe proxies to mongo-express (8091) and
redpanda-console (8092) for the `dev` profile; prod does not mount it.

## Session

`/auth/login` returns a 24 h JWT. The console keeps it in memory and in `sessionStorage` only when the token's
`role` claim is `SUPERADMIN`. A 401 from any request clears the session and shows the login again on the
same page; there is no refresh flow.

## Structure

- `src/api/`: `client.ts` (`api()`, `ApiError`, session store), `auth.ts` (`login`, `useSession`)
- `src/pages/`: screens (`Login.tsx`)
- `src/layout/`: the signed-in frame (`Shell.tsx`)
- `src/components/ui/`: coss ui components, vendored by the shadcn CLI (`npx shadcn@latest add @coss/<name>`)
- `src/index.css`: Tailwind entry and theme tokens
- `nginx.conf`, `nginx.dev-tools.conf`, `Dockerfile`: the container
