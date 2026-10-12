# CreditCardApplication on Cloudflare Containers (single container)

One Cloudflare Container runs the whole prototype stack under **supervisord**:

| Process       | Listens on            | Notes                                                        |
|---------------|-----------------------|--------------------------------------------------------------|
| `postgres`    | 127.0.0.1:5432        | data in `/data/postgres` (initdb on first boot)              |
| `minio`       | 127.0.0.1:9000        | data in `/data/minio`; Chainguard build of MinIO             |
| `onfido-mock` | 127.0.0.1:8081        | WireMock with `mock/onfido` stubs (the app needs an Onfido)  |
| `bootstrap`   | one-shot              | DB role + database, bucket, **`flyway migrate`**, then starts the app |
| `app`         | 0.0.0.0:8080          | Spring Boot jar, heap capped at 1.5 GiB                      |

A Worker (`src/index.ts`) sits in front at `creditcardapplication.mettyoung.com` and forwards to **one**
instance (`singleton`): normal requests to `:8080`, S3-signed requests (presigned browser uploads) to `:9000`.

Files: `Dockerfile`, `supervisord.conf`, `entrypoint.sh`, `bootstrap.sh`,
`wrangler.jsonc`, `src/index.ts`, `package.json`, `tsconfig.json`.

## ⚠️ Read this first: data is NOT persistent

Cloudflare Containers have **ephemeral disk only**. `/data/postgres` and `/data/minio` live on the instance's
disk (standard-1 = 8 GB) and are **wiped** whenever the instance stops:

- after `sleepAfter` of no traffic (set to **30 min** in `src/index.ts`),
- on every `wrangler deploy` (the rollout replaces the instance),
- whenever Cloudflare restarts/moves the instance (host maintenance, crash).

Each cold start = fresh empty database (Flyway re-creates the schema) and an empty bucket. That is fine for a
demo; it is **not** fine for real applicant data. Options if you need persistence later:

1. Move Postgres out (Neon / Supabase / any managed Postgres, optionally via Hyperdrive) and object storage to
   **R2** (S3-compatible, so it is `APP_STORAGE_ENDPOINT` + keys, no code). Recommended.
2. Container **snapshots** (public beta, Sept 2026): only with the `durable_object` scheduling policy; immutable,
   30-day TTL, not memory/process state. You would need to stop Postgres cleanly, snapshot, and restore on start.
   Not wired up here.
3. A FUSE mount to R2 — not suitable for a Postgres data directory.

Also: only `max_instances: 1`. Never raise it — a second instance would be a second, separate, empty database.

## Requirements

- **Workers Paid plan** ($5/month minimum) — Containers are not available on the free plan. Usage beyond the
  included allotment is billed (memory/CPU/disk per running second; standard-1 = 1/2 vCPU, 4 GiB, 8 GB).
  Sleeping instances are not billed.
- `mettyoung.com` must be a zone on the same Cloudflare account (for the Custom Domain).
- **Docker running locally** (Docker Desktop on the Mac) — `wrangler deploy` builds the image and pushes it to
  Cloudflare's registry. Image must be linux/amd64; the Dockerfile pins that (on Apple Silicon it builds under
  emulation, so the first build is slow).
- Node.js **22+** (current Wrangler refuses older Node).
- The build context is the repo root, so the root `.dockerignore` applies; it re-includes `mock/onfido`
  (`!mock/onfido`) and excludes `cf-container/node_modules`. Keep those lines.

## Deploy (from the Mac)

```bash
cd ~/IdeaProjects/CreditCardApplication/cf-container
npm install
npx wrangler login            # browser OAuth; or export CLOUDFLARE_API_TOKEN=... (Workers Scripts:Edit,
                              # Workers Containers:Edit, Zone:Workers Routes:Edit, Zone DNS:Edit for mettyoung.com)
npx wrangler whoami           # confirm the right account
# optional – otherwise random passwords are generated per boot (fine: nothing outside the container uses them)
npx wrangler secret put POSTGRES_PASSWORD
npx wrangler secret put MINIO_ROOT_PASSWORD
npx wrangler deploy
```

`wrangler deploy` uploads the Worker, builds + pushes the image, creates the Container application and the Custom
Domain `creditcardapplication.mettyoung.com` (DNS record + certificate are created for you).

The first deploy takes several minutes to provision. Then:

```bash
npx wrangler containers list          # status
npx wrangler containers images list
npx wrangler tail                      # Worker logs
```

Container logs (supervisord + all processes): dashboard → **Workers & Pages → Containers → creditcardapplication**.

**Cold start**: the first request after sleep/deploy boots Postgres, runs Flyway and starts the JVM on 1/2 vCPU —
expect **1–3 minutes**. The Worker waits up to 4 minutes and otherwise returns `503` with `Retry-After`.

### Dashboard equivalents

- Enable/check plan: dashboard → **Workers & Pages → Plans** → Workers Paid.
- Containers: dashboard → **Workers & Pages → Containers** (created by `wrangler deploy`, nothing to click first).
- Custom domain (if you prefer the UI or removed `routes`): **Workers & Pages → creditcardapplication → Settings →
  Domains & Routes → Add → Custom domain → `creditcardapplication.mettyoung.com`**.

## Configuration

`wrangler.jsonc` → `vars` (non-secret) and `wrangler secret put` (secret) are passed to the container as env vars:

| Name                      | Default                                     | Meaning |
|---------------------------|---------------------------------------------|---------|
| `PUBLIC_URL`              | `https://creditcardapplication.mettyoung.com` | Public origin. Used as the S3 endpoint so presigned upload URLs are browser-reachable; the Worker routes S3-signed requests to MinIO. Empty → MinIO internal only, browser uploads won't work. |
| `MINIO_BUCKET`            | `credit-card-documents`                     | Bucket (also the path prefix the Worker routes to MinIO) |
| `APP_UI_TIMELINE_ENABLED` | `false`                                     | Live timeline shows vendor outcomes; keep off in public |
| `POSTGRES_PASSWORD`       | random per boot                             | secret |
| `MINIO_ROOT_PASSWORD`     | random per boot                             | secret |

Caveat of `PUBLIC_URL`: the app's own S3 calls (HEAD after upload, bucket CORS) also go to the public URL, i.e.
out through Cloudflare and back into the same container. It is the same design as `compose.deploy.yaml`'s
`S3_PUBLIC_ENDPOINT`. The bucket itself is created locally by `bootstrap.sh`, so startup does not depend on it.

Instance size: `standard-1` (4 GiB) in `wrangler.jsonc`. `basic` (1 GiB) is too small for JVM + WireMock +
Postgres + MinIO. If memory is tight, use `standard-2` (1 vCPU, 6 GiB), which also halves cold-start time.

## Run the same image locally

```bash
cd ~/IdeaProjects/CreditCardApplication
docker build --platform linux/amd64 -f cf-container/Dockerfile -t creditcard-cf .
docker run --rm -p 8080:8080 creditcard-cf     # http://localhost:8080 (uploads won't work without PUBLIC_URL)
```

Or `npx wrangler dev` in `cf-container/` (runs the Worker + container locally via Docker).

## Not done / out of scope

- Real Onfido: set `app.onfido.base-url`/token via env and drop the `onfido-mock` program.
- No persistence (see top). No backups. Treat this as a disposable demo environment.
