# Aviation API Wrapper

A Spring Boot 4 microservice that returns airport details by ICAO code. Data comes from [aviationweather.gov](https://aviationweather.gov/data/api/#/Data/dataAirport), behind a Redis cache, retries, a circuit breaker and a rate limiter. The provider sits behind a port, so another data source is one new adapter.

## Run it

Requires Docker. Java 21 is only needed to run outside Docker.

```bash
scripts/run.sh                 # build the image, start app + Redis, wait until ready
curl -i http://localhost:8080/api/v1/airports/KJFK
scripts/run.sh down
```

`docker compose up --build` does the same. Swagger UI is at http://localhost:8080/swagger-ui.html. Health and metrics are at http://127.0.0.1:8081/actuator/health and `/actuator/prometheus`, bound to localhost only. Settings come from env vars; see [.env.example](.env.example).

**Development:** `scripts/dev.sh` starts only Redis (`docker-compose.dev.yml`, `127.0.0.1:6379`), then runs the app with `./mvnw spring-boot:run` and the `dev` profile. `scripts/dev.sh down` stops Redis. From the IDE, run `AviationApiApplication` with the `dev` profile instead.

## API

`GET /api/v1/airports/{icao}`. The code must be exactly four letters or digits; it is upper-cased.

```json
{
  "icao": "KJFK",
  "iata": "JFK",
  "name": "NEW YORK/JOHN F KENNEDY INTL",
  "location": { "region": "NY", "country": "US", "latitude": 40.6399, "longitude": -73.7787, "elevationM": 4 },
  "runways": [ { "id": "04L/22R", "lengthFt": 12079, "widthFt": 200, "surface": "CONCRETE" } ],
  "source": "aviationweather.gov",
  "retrievedAt": "2026-11-24T14:06:22Z"
}
```

Every response carries `X-Request-Id`. A 200 also has `X-Cache: HIT | MISS | STALE`. Example requests are in [requests.http](requests.http).

### Error handling

Errors are [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) with a `requestId`, including Spring's own 404 and 405. They never contain stack traces, exception messages or upstream text.

| Status | When |
| --- | --- |
| 200 | Found. `Cache-Control: max-age` is the time left until the entry goes stale (max 3600). While the upstream is failing, an older cached copy is served as `STALE` with `no-cache` |
| 400 | Not a valid ICAO code. This also blocks the upstream's list and wildcard syntax (`KJ*`, `KJFK,EGLL`) |
| 404 | The upstream says no such airport (204 or `[]`). Remembered for 10 min, `max-age=300` |
| 502 | Upstream 5xx, 403, other 4xx, connection error or unreadable body, after retries |
| 503 + `Retry-After` | Upstream not called: circuit open or local rate limit reached; or the upstream sent 429 with a long `Retry-After` |
| 504 | The upstream timed out on every attempt |

Any 502, 503 or 504 becomes a 200 `STALE` when an older copy is cached. A "not found" never does.

## Architecture

```
Client ─► API (validation, request ID, Problem Details)
             ▼
        AirportService ── cache first; stale copy if the provider fails
          │                         │
   AirportCache (port)       AirportProvider (port)
          ▼                         ▼
        Redis             AviationWeather adapter: retry ► circuit breaker ► rate limiter ► JDK HttpClient
                                    ▼
                           aviationweather.gov
```

| Package | Contents |
| --- | --- |
| `api` | Controller, response DTOs, `GlobalExceptionHandler`, `RequestIdFilter`, ICAO validation |
| `domain` | `Airport` records, `AirportService`, the `AirportProvider` and `AirportCache` ports, exceptions |
| `provider.aviationweather` | HTTP client, upstream DTOs, mapper, config |
| `provider.resilience` | `ResilientCall`: the guards around any provider call |
| `cache` | `RedisAirportCache` |
| `config` | Wiring, Resilience4j registries and metrics |

The domain knows nothing about HTTP, Redis or Resilience4j. Every provider failure is one `UpstreamException` with a `kind` (`TIMEOUT`, `SERVER_ERROR`, `RATE_LIMITED`, `CIRCUIT_OPEN`...), so the retry, the circuit breaker and the error handler each decide from a single field.

**Adding a provider:**
1. Implement `AirportProvider`.
2. Wrap its calls in a `ResilientCall` named after it, so it gets its own retry, circuit breaker and rate limiter.
3. Register it with `@ConditionalOnProperty(name = "app.provider", havingValue = "...")`.
4. Set `app.provider`.

Nothing else changes.

### Resilience

| Guard | Setting | Why |
| --- | --- | --- |
| HTTP timeouts | Connect 1 s, response 1.5 s | A slow upstream can't hold a request for long |
| Retry | 3 attempts, 200 ms × 2 backoff with jitter. Retries timeouts, connection errors and 5xx. Retries a 429 only when `Retry-After` ≤ 2 s | Rides out short blips. Worst case is about 5.4 s |
| Circuit breaker | Window 20, min 10 calls, opens at 50 % failures, open 30 s, then 2 trial calls. Counts timeouts, connection errors, 5xx and 403 | Stops hammering an upstream that is down, and answers 503 at once |
| Rate limiter | 80 calls a minute per instance, refilled 7 at a time every 5 s | The upstream blocks clients above 100 a minute. Retries spend permits too |

All numbers are in [application.yml](src/main/resources/application.yml) under `app.resilience`.

### Caching

Redis key `airport:v1:{ICAO}`, value JSON `{airport, fetchedAt}`.
- **Fresh for 24 h:** served without calling the upstream.
- **Kept for 7 days:** after 24 h the upstream is asked again; if it fails, the old copy is served as `STALE`.
- **Not found:** remembered for 10 min, so repeated unknown codes don't use up the upstream's limit.
- **Redis down:** errors (200 ms command timeout) are logged, counted and treated as a miss.

### Observability and security

- **Request ID:** from `X-Request-Id` if safe (≤ 64 chars of `[A-Za-z0-9-]`), else a UUID. It appears in every log line and every error body.
- **Logs:** plain text locally, ECS JSON in Docker. Upstream calls log code, status and latency; upstream bodies are never logged.
- **Metrics:** `airport.lookups{outcome,cache}`, `airport.upstream.requests{provider,outcome}`, `airport.cache.errors`, Resilience4j metrics and `http.server.requests`.
- **Health:** readiness doesn't include Redis or the upstream, because the service still answers without them.
- **Actuator:** in Docker it runs on port 8081, bound to localhost. Without the `docker`/`dev` profile, only health and info are exposed.
- **Container:** non-root user, read-only filesystem, `no-new-privileges`.
- **Redis:** requires a password; the Compose default `change-me` is a placeholder.
- **Upstream:** HTTPS, no redirects, a custom `User-Agent`.
- **Auth and per-client limits:** none, because the brief excludes user management; a gateway would own both.
- **Known abuse path:** one client requesting many different valid-looking codes can use up the shared 80/min upstream budget, so other clients' cache misses get 503. Caching "not found" only helps with repeated codes. A per-client limit at the gateway is the fix.
- **Swagger UI and `/v3/api-docs`** are public on port 8080. They describe only the public API; disable them with `springdoc.api-docs.enabled=false` and `springdoc.swagger-ui.enabled=false` if that's unwanted.

## Tests

```bash
./mvnw verify            # format check, unit + integration tests, JaCoCo reports
./mvnw test              # unit tests only, no Docker needed
```

Integration tests use Testcontainers to start a real Redis and a WireMock container standing in for aviationweather.gov. The fixture is a real KJFK response saved with curl. They cover:
- MISS then HIT; 404 then served from the not-found marker; 400 without an upstream call.
- 500, 500, 200 retried to success; short and long 429.
- The circuit opening and recovering.
- A slow upstream → 504.
- `STALE` on failure; Redis paused; the local rate limit.
- A corrupt cache entry; Spring's 404/405.

Unit tests cover the mapper, validation, `Retry-After` parsing, the service's cache rules, the error handler, the guards and the HTTP client. Coverage reports go to `target/site/jacoco` and `target/site/jacoco-it`. CI ([ci.yml](.github/workflows/ci.yml)) runs `./mvnw verify` on every PR and push to `main`, then builds the image and smoke-tests it with `scripts/run.sh`.

## Decisions and assumptions

- **Unknown code → 404, not 204:** 204 means success and can't carry an error body.
- **Redis as the only cache:** shared by all instances. A hit costs about 1 ms against 100–500 ms upstream, so an in-memory layer would add complexity for little gain.
- **JDK `HttpClient`, not `RestClient`:** `RestClient`'s read timeout cancelled fast requests at exactly the timeout when called from virtual threads (reproduced against the real upstream). It also reported timeouts as generic I/O errors.
- **No separate time limit or bulkhead:** retries × read timeout already bound a lookup (about 5.4 s). Virtual threads and the rate limiter bound load.
- **No MapStruct or Lombok:** records cover the value types, and the mapper is mostly rules that MapStruct would hide.
- **Data assumptions:** `elev` is in metres and runway `dimension` is `LENGTHxWIDTH` in feet, as in the real response. Surface codes other than `A` and `C` map to `UNKNOWN`.
- **Freshness:** airport data rarely changes, so 24 h fresh and 7 days stale are acceptable.
- **Upstream latency:** measured on 2026-11-24 over 20 requests, the median was 0.38 s but p90 was 10 s. Slow responses take about 10 s, not 2 s, so a longer timeout wouldn't help: failing fast at 1.5 s and retrying does. A lookup that is slow on all three attempts returns 504, or `STALE` if cached.

**Next steps:**
- A shared rate limiter in Redis; today the limit is per instance, so with N replicas set `UPSTREAM_RATE_LIMIT_PER_MINUTE` to 80/N.
- Per-client rate limiting (at a gateway, or with a Redis-backed bucket keyed by client).
- Request coalescing for concurrent misses.
- A circuit breaker around Redis.
- CodeQL and Trivy in CI.
- Tracing.

## AI usage

Built with Claude Code. Claude Opus wrote the plan and the code; Claude Sonnet and Opus reviewed the plan and then the code. Their findings were checked against the code before anything was changed. I made the decisions, including 404 for unknown codes, Redis only, the scope cut and this simplification pass. The upstream's behaviour (JSON array, 204 for unknown codes, rate limit) was checked by hand with curl, and the `RestClient` timeout problem was found by running against the real API.
