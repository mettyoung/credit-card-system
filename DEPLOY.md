# Self-hosting the prototype (Docker Compose + Cloudflare Tunnel)

Runs on a machine behind NAT with **no inbound ports**: `cloudflared` dials out to Cloudflare, and Cloudflare
terminates TLS for the public hostname. Everything lives in `compose.deploy.yaml`; the dev stack in `compose.yaml`
(used by `./gradlew bootRun`) is untouched.

| Service       | What                                         | Reachable from                    |
|---------------|----------------------------------------------|-----------------------------------|
| `app`         | this Spring Boot app, built from `Dockerfile`| Docker network; `127.0.0.1:8088`  |
| `postgres`    | Postgres 17, volume `pgdata`, 512 MB cap     | Docker network only               |
| `flyway`      | one-shot `migrate` of `src/main/resources/db/migration` | -                      |
| `minio`       | S3 object store, volume `miniodata`, 512 MB  | Docker network only               |
| `onfido-mock` | WireMock stand-in for Onfido (`mock/onfido`) | Docker network only               |
| `cloudflared` | Cloudflare Tunnel, profile `tunnel`          | outbound only                     |

## 1. Configure

```bash
cp .env.example .env          # git-ignored; fill in POSTGRES_PASSWORD and MINIO_ROOT_PASSWORD
docker compose -f compose.deploy.yaml config -q
```

## 2. Run (without the tunnel)

```bash
docker compose -f compose.deploy.yaml up -d --build
docker compose -f compose.deploy.yaml ps
docker compose -f compose.deploy.yaml logs flyway   # "Successfully applied N migrations"
open http://127.0.0.1:8088
```

## 3. Create the Cloudflare Tunnel

1. https://one.dash.cloudflare.com -> **Zero Trust -> Networks -> Tunnels -> Create a tunnel**.
2. Connector type **Cloudflared**, name it (e.g. `creditcard-mac`), **Save tunnel**.
3. Environment **Docker**. Copy only the token (the long string after `--token`) into `.env`:
   `CLOUDFLARE_TUNNEL_TOKEN=...` Do not commit it.
4. Start the connector:
   ```bash
   docker compose -f compose.deploy.yaml --profile tunnel up -d
   docker compose -f compose.deploy.yaml logs cloudflared   # "Registered tunnel connection" (x4)
   ```
   The tunnel shows **HEALTHY** in the dashboard.

## 4. Public hostname (needs a domain on Cloudflare)

Tunnel -> **Public Hostname** (newer UI: **Published application routes**) -> **Add**:

- Subdomain `app`, Domain `example.com`, Path empty
- Service **Type `HTTP`**, **URL `app:8080`** (the compose service name, resolved on the Docker network)

Cloudflare creates the proxied CNAME `app.example.com -> <tunnel-id>.cfargotunnel.com` itself. Then set
`PUBLIC_APP_URL=https://app.example.com` in `.env` and `docker compose -f compose.deploy.yaml up -d` again
(it is the bucket's CORS origin).

Until a domain is added there is **no public URL**.

## 5. Document uploads (second hostname)

The browser uploads ID documents straight to the object store with a pre-signed URL built from
`APP_STORAGE_ENDPOINT`. Inside Docker that is `http://minio:9000`, which a browser cannot reach. For uploads to
work publicly, add a second public hostname on the same tunnel:

- `s3.example.com` -> Type `HTTP`, URL `minio:9000`

and set `S3_PUBLIC_ENDPOINT=https://s3.example.com` in `.env`, then `up -d` again. Everything else in the app
works without it.

## Notes

- All services are `restart: unless-stopped`; keep Docker Desktop set to start at login and the Mac awake
  (System Settings -> Battery/Energy -> prevent automatic sleeping when the display is off).
- The live timeline (`APP_UI_TIMELINE_ENABLED`) is off by default here: it shows vendor outcomes an applicant
  must never see.
- Data lives in the `creditcard-deploy_pgdata` and `creditcard-deploy_miniodata` volumes;
  `docker compose -f compose.deploy.yaml down` keeps them, `down -v` deletes them.
