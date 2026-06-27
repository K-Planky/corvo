# Othello thin client (React + Vite)

A **thin client only** — all game logic is server-side (the server is authoritative). This M0.3
skeleton just proves the client builds and can reach the API across origins in dev. The real game
UI arrives in Milestone 4.

## Prerequisites
- Node.js ≥ 20 LTS (ships with npm).

## Develop
The dev server proxies `/health` and `/api` to the Spring backend on `localhost:8080`
(see `vite.config.ts`), so the cross-origin dev call works without CORS.

```bash
# 1. start the backend (from the repo root): docker compose up -d && ./mvnw spring-boot:run
# 2. then:
npm install
npm run dev        # http://localhost:5173 — shows the server's /health status
```

## Build
```bash
npm run build      # type-checks (tsc -b) then bundles to dist/
```

## Production
The SPA is served same-origin with the API behind the reverse proxy (spec §13), so no proxy or
CORS configuration is needed in production.
