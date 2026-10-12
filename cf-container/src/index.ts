import { Container, getContainer } from "@cloudflare/containers";

/**
 * Front door for creditcardapplication.mettyoung.com.
 *
 * Exactly ONE container instance ("singleton") runs the whole stack: Spring Boot on :8080, Postgres, MinIO on
 * :9000, Flyway (once) and the Onfido mock, under supervisord. Everything is in one instance on purpose: the
 * database lives on that instance's (ephemeral) disk, so a second instance would be a second, empty database.
 *
 * Routing:
 *   - S3-signed requests (presigned browser uploads, and the app's own S3 calls once PUBLIC_URL is set) -> :9000
 *   - everything else -> :8080
 */

export interface Env {
  CREDITCARD: DurableObjectNamespace<CreditCardContainer>;
  PUBLIC_URL?: string;              // var: https://creditcardapplication.mettyoung.com (enables browser uploads)
  MINIO_BUCKET?: string;            // var
  APP_UI_TIMELINE_ENABLED?: string; // var
  POSTGRES_PASSWORD?: string;       // secret (optional; generated per boot if unset)
  MINIO_ROOT_PASSWORD?: string;     // secret (optional; generated per boot if unset)
}

const APP_PORT = 8080;
const S3_PORT = 9000;
// A JVM on 1/2 vCPU, after initdb + flyway, needs far longer than the library's 20 s default.
const COLD_START = { instanceGetTimeoutMS: 60_000, portReadyTimeoutMS: 240_000 };
// Hash of the envVars the running container was started with (never the values themselves).
const ENV_FINGERPRINT_KEY = "envFingerprint";

export class CreditCardContainer extends Container<Env> {
  defaultPort = APP_PORT;
  requiredPorts = [APP_PORT];
  // Stop after 30 min with no requests. Stopping WIPES the disk (Postgres + MinIO data). Raise it to keep data
  // longer (you pay for the running time), or lower it to save money if losing data is acceptable.
  sleepAfter = "30m";
  enableInternet = true; // needed for the app's S3 calls to the public endpoint when PUBLIC_URL is set

  private appReady = false;
  private starting: Promise<void> | null = null;

  constructor(ctx: DurableObjectState<{}>, env: Env) {
    super(ctx, env);
    const vars: Record<string, string> = {};
    for (const key of ["PUBLIC_URL", "MINIO_BUCKET", "APP_UI_TIMELINE_ENABLED", "POSTGRES_PASSWORD", "MINIO_ROOT_PASSWORD"] as const) {
      const value = env[key];
      if (typeof value === "string" && value.length > 0) vars[key] = value;
    }
    this.envVars = vars;
  }

  override onStart(): void {
    console.log("container started");
  }

  override onStop(): void {
    this.appReady = false;
    console.log("container stopped - its disk (and all data) is gone");
  }

  override onError(error: unknown): void {
    console.error("container error", error);
  }

  override async fetch(request: Request): Promise<Response> {
    const port = isS3Request(request, this.env.MINIO_BUCKET ?? "credit-card-documents") ? S3_PORT : APP_PORT;
    if (port === APP_PORT && !this.appReady) {
      try {
        this.starting ??= this.ensureStarted().finally(() => { this.starting = null; });
        await this.starting;
        this.appReady = true;
      } catch (e) {
        console.error("container not ready", e);
        return new Response(
          "The application is starting (cold start can take 1-3 minutes). Please retry shortly.\n",
          { status: 503, headers: { "retry-after": "20", "content-type": "text/plain" } },
        );
      }
    }
    return this.containerFetch(request, port);
  }

  /**
   * Container envVars only apply when the container STARTS. A `wrangler deploy` that changes only vars (e.g.
   * APP_UI_TIMELINE_ENABLED) restarts this Durable Object but can leave the old container running with the old
   * environment. So: remember a hash of the envVars the container was started with, and if the running container
   * was started with different ones (or unknown ones), restart it once. Restarting wipes its ephemeral disk.
   */
  private async ensureStarted(): Promise<void> {
    const fingerprint = await sha256(JSON.stringify(Object.entries(this.envVars ?? {}).sort()));
    const startedWith = await this.ctx.storage.get<string>(ENV_FINGERPRINT_KEY);
    if (startedWith !== fingerprint && this.ctx.container?.running) {
      console.log("container was started with different vars - restarting it so the current ones apply");
      await this.destroy();
      for (let i = 0; i < 60 && this.ctx.container?.running; i++) await new Promise((r) => setTimeout(r, 1000));
    }
    await this.startAndWaitForPorts({ ports: [APP_PORT], cancellationOptions: COLD_START });
    await this.ctx.storage.put(ENV_FINGERPRINT_KEY, fingerprint);
  }
}

async function sha256(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Requests signed for S3 (header or presigned query), or addressed to the bucket path-style. */
function isS3Request(request: Request, bucket: string): boolean {
  const url = new URL(request.url);
  if ((request.headers.get("authorization") ?? "").startsWith("AWS4-HMAC-SHA256")) return true;
  if (url.searchParams.has("X-Amz-Signature") || url.searchParams.has("X-Amz-Algorithm")) return true;
  return url.pathname === `/${bucket}` || url.pathname.startsWith(`/${bucket}/`);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    return getContainer(env.CREDITCARD, "singleton").fetch(request);
  },
} satisfies ExportedHandler<Env>;
