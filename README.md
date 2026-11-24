# Aviation API Wrapper

A Spring Boot 4 microservice that returns airport details by ICAO code. Data comes from [aviationweather.gov](https://aviationweather.gov/data/api/#/Data/dataAirport), behind a Redis cache, retries, a circuit breaker, a rate limiter, a bulkhead and a time limit. The provider sits behind a port, so another data source is one new adapter.

```bash
curl -i http://localhost:8080/api/v1/airports/KJFK
```

## Quick start

Requirements: Docker. Java 21 is needed only for running outside Docker.

```bash
scripts/run.sh            # builds the image, starts app + Redis, waits until ready
scripts/run.sh down       # stops everything
```

The same can be done with `docker compose up --build`.

| URL | What |
| --- | --- |
| http://localhost:8080/api/v1/airports/{icao} | The API |
| http://localhost:8080/swagger-ui.html | Swagger UI / OpenAPI |
| http://127.0.0.1:8081/actuator/health | Health (bound to localhost only) |
| http://127.0.0.1:8081/actuator/prometheus | Metrics |

Settings come from environment variables; see [.env.example](.env.example).

## Development setup

`docker-compose.dev.yml` runs only Redis, on `127.0.0.1:6379`, so the app can run from the IDE.

```bash
scripts/dev.sh            # starts Redis, then ./mvnw spring-boot:run with the dev profile
scripts/dev.sh down       # stops Redis
```

From IntelliJ: start Redis with `docker compose -f docker-compose.dev.yml up -d`, then run `AviationApiApplication` with the `dev` profile.

## API

`GET /api/v1/airports/{icao}`. The code must be exactly four letters or digits and is upper-cased.

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

Every response has `X-Request-Id` (yours if you send a safe one, otherwise a new UUID). A 200 also has `X-Cache: HIT | MISS | STALE`.

### Errors

Errors are [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) with a `requestId`, including Spring's own errors (unknown path 404, wrong method 405). They never contain stack traces, exception messages or upstream text.

| Status | When | Headers |
| --- | --- | --- |
| 200 | Found. Fresh, or a stale copy while the provider is failing | `Cache-Control: max-age` = time left until stale (max 3600); `no-cache` when `STALE` |
| 400 | Not a valid ICAO code (also blocks the upstream's list and wildcard syntax) | |
| 404 | The provider says no such airport (upstream 204 or `[]`); remembered for 10 min | `Cache-Control: max-age=300` |
| 502 | Upstream 5xx or 403 after retries, other 4xx, or a body we can't parse | |
| 503 | We didn't call upstream: circuit open, local rate limit, too many lookups in flight, or upstream 429 with a long `Retry-After` | `Retry-After` |
| 504 | The upstream timed out, or the whole lookup ran past 6 s | |

Any 502, 503 or 504 becomes a 200 `STALE` when an older cached copy exists. A "not found" never does.

```bash
curl -i http://localhost:8080/api/v1/airports/KJFK     # 200, X-Cache: MISS, then HIT
curl -i http://localhost:8080/api/v1/airports/ZZZZ     # 404 airport-not-found
curl -i 'http://localhost:8080/api/v1/airports/KJ%2A'  # 400 invalid-icao
```

[requests.http](requests.http) has the same requests for IntelliJ's HTTP client.

## Running the tests

```bash
./mvnw verify               # format check, unit tests, integration tests, JaCoCo reports
./mvnw test                 # unit tests only, no Docker needed
./mvnw verify -DskipITs     # everything except the integration tests
./mvnw spotless:apply       # fix formatting
```

Integration tests need Docker. They use Testcontainers to start a real Redis and a [WireMock](https://wiremock.org/) container that plays aviationweather.gov; its main fixture is a real KJFK response saved with curl. Coverage reports end up in `target/site/jacoco` (unit) and `target/site/jacoco-it` (integration).

| # | Scenario | Expected |
| --- | --- | --- |
| 1 | Known ICAO, twice | 200 `MISS`, then 200 `HIT`; upstream called once; key in Redis |
| 2 | Unknown ICAO (upstream 204), twice | 404 both times; upstream called once |
| 3 | `KJ*`, `KJFK,EGLL`, `KJF`, `KJFKX` | 400; upstream never called |
| 4 | Upstream 500, 500, 200 | 200 after 3 calls |
| 5 | 429 with `Retry-After: 1`, then 200; separately 429 with `Retry-After: 30` | 200 after waiting; 503 + `Retry-After: 30` after one call, circuit still closed |
| 6 | Upstream always 500 | 502, then the circuit opens: 503 without calling upstream; readiness stays UP |
| 7 | Circuit open, then upstream recovers | Half-open trial succeeds, 200 |
| 8 | Upstream slower than the read timeout | 504 after 3 attempts; no upstream calls continue after the response |
| 8b | Upstream slower than the overall time limit | 504 within the limit; the attempt is cancelled and never retried |
| 9 | Upstream failing, 25 h old entry in Redis | 200 `STALE`, `Cache-Control: no-cache` |
| 10 | Redis paused | 200 straight from upstream; cache-error metric goes up |
| 11 | Local rate limit of 2/min | Third lookup is 503 and never reaches upstream |
| | Also: corrupt Redis entry, POST and unknown path | Treated as a miss; 405 and 404 as Problem Details with `requestId` |

Unit tests cover the mapper, ICAO validation, `Retry-After` parsing, the service's cache rules with fakes, the error handler, the guard pipeline (`ResilientCallTest`: time limit, bulkhead, 429 handling, which failures count toward the circuit) and the HTTP client against an in-process WireMock (one case per row of the failure table).

The test profile shrinks every timeout and threshold so the suite runs in seconds.

## Configuration

All under `app.*` in [application.yml](src/main/resources/application.yml). The usual ones can be set by env var.

| Property | Default | Env var |
| --- | --- | --- |
| `app.provider` | `aviationweather` | |
| `app.upstream.aviationweather.base-url` | `https://aviationweather.gov` | `UPSTREAM_BASE_URL` |
| `app.upstream.aviationweather.connect-timeout` / `read-timeout` | 1 s / 1.5 s | |
| `app.resilience.time-limit` | 6 s | |
| `app.resilience.bulkhead.max-concurrent` | 25 | |
| `app.resilience.retry.max-attempts` / `initial-backoff` / `max-retry-after` | 3 / 200 ms / 2 s | |
| `app.resilience.circuit-breaker.*` | window 20, min 10 calls, 50 %, open 30 s, 2 half-open trial calls | |
| `app.resilience.rate-limiter.limit-per-minute` / `refresh-period` | 80 / 5 s | `UPSTREAM_RATE_LIMIT_PER_MINUTE` |
| `app.cache.fresh-for` / `ttl` / `not-found-ttl` | 24 h / 7 d / 10 min | |
| `spring.data.redis.host` / `password` | `localhost` / none | `REDIS_HOST` / `REDIS_PASSWORD` |

## Architecture

```
Client ─► API layer (validation, request ID, Problem Details)
             │
             ▼
        AirportService ── cache first; stale copy if the provider fails
          │         │
   1. read cache    2. on miss
          ▼         ▼
   AirportCache   AirportProvider (port)
   (port)           │
      │             ▼
      ▼          AviationWeather adapter
    Redis        time limit ► bulkhead ► retry ► circuit breaker ► rate limiter ► HTTP
                    │
                    ▼
               aviationweather.gov
```

Packages under `io.github.marinco.aviation`:

| Package | Contents |
| --- | --- |
| `api` | Controller, response DTOs, `GlobalExceptionHandler`, `RequestIdFilter`, ICAO validation |
| `domain` | `Airport` records, `AirportService`, the `AirportProvider` and `AirportCache` ports, exceptions |
| `provider.aviationweather` | HTTP client, upstream DTOs, mapper, config |
| `provider.resilience` | `ResilientCall`: the guards around any provider call |
| `cache` | `RedisAirportCache` |
| `config` | Wiring, Resilience4j registries and metrics, upstream health |

The domain knows nothing about HTTP, Redis or Resilience4j. Provider failures come out as domain exceptions (`UpstreamTimeoutException`, `UpstreamUnavailableException`, and so on), never as library types.

### Adding a second provider

1. Implement `AirportProvider` in a new package, for example `provider.airportdb`.
2. Wrap its calls in a `ResilientCall` named after the provider. It gets its own circuit breaker, retry, rate limiter, bulkhead and metrics.
3. Register it in a `@Configuration` with `@ConditionalOnProperty(name = "app.provider", havingValue = "airportdb")`.
4. Set `app.provider=airportdb`. The service, cache and API don't change.

### Main decisions

| Decision | Why |
| --- | --- |
| Unknown ICAO → 404, not 204 | 204 means success and can't carry an error body; mirroring the upstream would leak its quirks to our clients |
| Redis as the only cache | Shared by every instance, survives restarts, and a hit costs about 1 ms against 100–500 ms upstream. An in-memory layer in front was considered and left out: it would let instances disagree and complicate the stale and not-found rules, for a sub-millisecond gain |
| Resilience4j, composed by hand | The guard order is explicit in one class (`ResilientCall`) and tested. Spring's own `@Retryable` has no circuit breaker or rate limiter |
| Serve stale data on failure | Airport data rarely changes, so a day-old answer beats an error |
| Virtual threads | Upstream calls block; virtual threads make that cheap |
| JDK `HttpClient` directly, not Spring's `RestClient` | `RestClient`'s read timeout for the JDK client cancelled fast requests at exactly the timeout when called from virtual threads (reproduced against the real upstream), and it reports timeouts as generic I/O errors. The JDK client's per-request timeout throws `HttpTimeoutException`, which maps cleanly to 504 |
| Spring Boot 4.2 / Java 21 | Current Boot line; Java 21 is LTS |

## Resilience and error handling

Every cache miss passes these guards, outermost first:

| Guard | Setting | Why |
| --- | --- | --- |
| Time limit | 6 s per lookup on a virtual thread. On expiry the call is cancelled (the HTTP call and retry backoff are interrupted, nothing is retried) and the lookup returns 504. The call is also cancelled if the request thread is interrupted | Caps the worst case below common gateway timeouts. The worst case of the guards below (3 × 1.5 s + backoff, about 5.4 s) fits inside it |
| Bulkhead | 25 concurrent lookups per instance, no waiting | Protects this service's threads and memory |
| Retry | 3 attempts, 200 ms × 2 backoff with jitter. Retries timeouts, I/O errors and 5xx. Retries 429 only when `Retry-After` ≤ 2 s, waiting that long | Rides out short blips |
| Circuit breaker | Window 20, min 10 calls, opens at 50 %, open 30 s, then 2 trial calls | Stops hammering an upstream that is down; answers 503 at once |
| Rate limiter | 80 calls a minute per instance, refilled 7 at a time every 5 s; 100 ms wait | The upstream blocks clients above 100 a minute. Refilling in small steps means a burst across a minute boundary can't reach 160. Retries spend permits too |
| HTTP timeouts | Connect 1 s, response 1.5 s | One slow call can't eat the whole time budget |

How each failure is treated:

| Failure | Retried | Counts toward opening the circuit | Status |
| --- | --- | --- | --- |
| Timeout | Yes | Yes | 504 |
| Connection error | Yes | Yes | 502 |
| Upstream 5xx | Yes | Yes | 502 |
| Upstream 403 (we may be blocked; logged at ERROR) | No | Yes | 502 |
| Upstream 429 | If `Retry-After` ≤ 2 s | No | 503 + the upstream's `Retry-After` |
| Other 4xx, unparsable body | No | No | 502 |
| Circuit open | — | — | 503 + `Retry-After` = open duration |
| Local rate limit or bulkhead full | No | No | 503 + `Retry-After: 1` |

An upstream `Retry-After` is capped at 1 hour.

When attempts fail in different ways, the last one decides the status.

## Caching

- Key `airport:v1:{ICAO}`, value JSON `{airport, fetchedAt}`. `v1` lets the format change without a flush.
- Fresh for 24 h: served without calling the upstream (`HIT`).
- Kept for 7 days: after 24 h the upstream is asked again. If it fails, the old copy is served as `STALE`.
- "Not found" is remembered for 10 min, and always replaces a stale copy.
- Redis failures (200 ms command timeout, 500 ms connect timeout) and unreadable entries are logged, counted and treated as a miss. The service keeps answering from the upstream.
- The service takes a `Clock`, so tests can age entries.

## Observability

- **Request ID**: `X-Request-Id` is accepted if it is at most 64 characters of `[A-Za-z0-9-]`, otherwise generated. It goes into every log line (MDC), including logs from the upstream call's own thread, and into every error body.
- **Logs**: plain text locally, ECS JSON in Docker. Each upstream call logs the code, outcome and latency; upstream bodies are never logged.
- **Metrics** (`/actuator/prometheus`):
  - `airport.lookups{outcome, cache}`
  - `airport.upstream.requests{provider, outcome}` (timer; outcome is `found`, `not_found`, `timeout`, `time_limit`, `io_error`, `server_error`, `forbidden`, `rate_limited`, `client_error`, `bad_response` or `not_called_*`)
  - `airport.cache.errors{operation}`
  - Resilience4j metrics for every guard
  - `http.server.requests`
- **Health**: readiness doesn't include Redis or the upstream, since the service still answers without them. `/actuator/health` shows both: Redis, and `upstream` with the circuit state (`DEGRADED` while it's open).
- In Docker, actuator runs on port 8081, published on `127.0.0.1` only. Without the `docker` or `dev` profile, only `health` and `info` are exposed and health details are hidden, so a bare `java -jar` doesn't leak metrics or Redis details on the public port.

## Security

- Strict input validation before anything else, which also prevents fan-out through the upstream's list and wildcard syntax.
- No stack traces, exception messages or upstream text in responses.
- HTTPS to the upstream, base URL from config, a custom `User-Agent` as the upstream asks, no redirects followed.
- Container:
  - Multi-stage build on `eclipse-temurin:21-jre-alpine`.
  - Runs as a non-root user with a read-only root filesystem and a tmpfs at `/tmp`.
  - `no-new-privileges` is set, and there's a health check.
- Redis has a password from an env var and no published port in the full stack. The `change-me` default in Compose is a placeholder: set `REDIS_PASSWORD` in `.env` for anything beyond a local run.
- Dependabot keeps Maven, Docker and Actions dependencies current.
- No inbound auth or per-client rate limits, because the brief excludes user management. An API gateway in front would own both.

## CI/CD

[.github/workflows/ci.yml](.github/workflows/ci.yml) runs on every pull request and every push to `main`:

1. **build-test**: `./mvnw verify`, which runs the format check, unit tests, Testcontainers integration tests and JaCoCo. Test and coverage reports are uploaded.
2. **docker**: builds the image once, starts it with `scripts/run.sh --no-build` (the README path), then checks readiness and that a malformed code gets 400. It never calls the real upstream.

To make these checks block merges, mark `Build and test` and `Docker image and smoke test` as required checks in the branch protection settings for `main`.

## Assumptions and limits

- `elev` is in metres and runway `dimension` is `LENGTHxWIDTH` in feet, as in the real KJFK response, which is checked into the test fixtures.
- Runway surface codes seen so far are `A` (asphalt) and `C` (concrete). Others map to `UNKNOWN` rather than a guess.
- Airport data changes rarely, so 24 h freshness and 7-day stale fallback are acceptable.
- The rate limiter counts per instance. With N replicas, set `UPSTREAM_RATE_LIMIT_PER_MINUTE` to 80/N.
- When several requests miss the cache for the same code at once, each one calls the upstream. Request coalescing is a next step.

## Next steps

Left out to fit the time budget:

- A shared rate limiter in Redis (for example Bucket4j), so replicas can't add up to more than 100/min.
- Request coalescing: concurrent misses for one code share a single upstream call.
- A circuit breaker around Redis, so an outage costs nothing per request instead of 200 ms timeouts.
- An in-memory cache in front of Redis: a decorator over `AirportCache`, found airports only, a TTL of 1–5 min, with per-layer metrics.
- CodeQL, dependency review and a Trivy image scan in CI. Actions pinned to commit SHAs. Image publishing to GHCR.
- A coverage gate (the reports are already produced).
- OpenTelemetry tracing, Grafana dashboards and alerts.
- An ArchUnit test that keeps the domain free of HTTP and provider code.

## AI usage

- **Tool:** this project was built with Claude Code.
- **Planning:** one model (Claude Opus 5.5) wrote the plan and the code. Claude Sonnet 5.5 reviewed the plan; before implementation, Opus 5.5 and Sonnet 5.5 each reviewed it again independently.
- **Code review:** after implementation, Opus 5.5 and Sonnet 5.5 each reviewed the code. Fixed from it:
  - Spring's own 404 and 405 errors now use our Problem Details shape.
  - A `null` upstream body or a `[null]` array is a 502, not a 500.
  - The time limit now cancels the call even when the caller goes away, and has its own test.
  - The rate limiter refills in small steps.
  - The circuit breaker allows 2 trial calls when it half-opens.
  - Production timeouts fit inside the time limit.
  - `Retry-After` is capped, and corrupt cache entries read as a miss.
  - Actuator details stay private outside Docker.
  - Tests use less tight timeouts, and the guards and HTTP client got unit tests.
- **Accepted from the plan reviews:**
  - The per-failure retry and circuit-breaker table.
  - Cancelling the timed-out call and carrying the request ID across threads.
  - Test-profile timings and a test for the rate limiter.
  - Spring Boot 4 specifics.
  - Building the CI image once.
  - The cut-line above.
- **Rejected:**
  - A second, in-memory cache.
  - Putting cache state in the response body. It stays in the `X-Cache` header.
  - Merging the two Compose files.
- **Found by testing, not by review:** Spring's `RestClient` read timeout misbehaving on virtual threads (see Main decisions).
- **Checked by hand:**
  - The upstream's behaviour, with curl: 200 with a JSON array, 204 for unknown codes, `Cache-Control: max-age=3600`.
  - That the library versions exist on Maven Central.
  - The test runs.
