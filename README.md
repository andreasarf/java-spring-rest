# java-spring-rest
![CI](https://github.com/andreasarf/java-spring-rest/actions/workflows/ci.yml/badge.svg?branch=main)
![Coverage](.github/badges/jacoco.svg)
![Branches](.github/badges/branches.svg)

Example for implementation Rest API using Spring Boot.

## Requirement
Java 25+ (Spring Boot 4)

## Build
`./mvnw clean install`

## Docker
Three Dockerfiles: `Dockerfile` (standard JVM image), `Dockerfile.aot` (JVM with Spring AOT and a Java 25 AOT
cache, starts ~4x faster) and `Dockerfile.native` (GraalVM native executable). See [Docker images](docs/docker.md).

```
docker build -t resourceapi . && docker run --rm -p 8080:8080 resourceapi
```

## Native image (GraalVM)
The JVM build (`./mvnw clean install`) is the main build and the only one CI runs. A native executable is an
optional alternative and is built manually. The application can be compiled ahead of time into a native executable with
[GraalVM](https://www.graalvm.org/) (GraalVM CE for JDK 25, with `native-image`, plus gcc and zlib headers):

```
export JAVA_HOME=/path/to/graalvm-jdk-25
./mvnw -Pnative -DskipTests native:compile
./target/resourceapi
```

See the [native image guide](docs/native-image.md) for prerequisites, running and configuring the executable
(some settings are fixed at build time), build memory and troubleshooting.

Spring Boot's `native` profile runs AOT processing and the `native-maven-plugin` builds `target/resourceapi`.
On a 4-core / 16 GB machine the build takes about 7 minutes and needs about 11 GB of memory; the executable
(about 190 MB) starts in about 0.25 s.

## Database
Database uses in-memory DB H2. It will automatically build and export the required tables and data.

The H2 console (`/h2-console`, JDBC URL `jdbc:h2:mem:test`) is available while running with devtools, e.g. `./mvnw spring-boot:run`. It is not served by the packaged jar.

## Calling SDLC systems (RestClient example)
`GET /api/v2/projects/{id}/external` looks the project up in its SDLC system with Spring's `RestClient`
(`SdlcProjectClient`, Jira-style `GET {baseUrl}/rest/api/2/project/{externalId}`):

- `200` with the system's `key`, `name` and `description`
- `404` if the project is unknown here or in the SDLC system
- `404` from the SDLC system counts as "unknown" only if it is the API's own JSON answer; an HTML 404
  (e.g. a wrong base URL) is a failure
- `502` if the SDLC system fails or answers without a project, if connecting takes longer than
  `spring.http.clients.connect-timeout`, or if the response stalls for longer than
  `spring.http.clients.read-timeout` (a per-read socket timeout, not a total deadline)
- `503` with `Retry-After` if no pooled connection frees up within
  `resourceapi.http-client.pool.connection-request-timeout` (our own limit, not the SDLC system's)

Requests go through a pooled Apache HttpClient 5 (`PoolingHttpClientConnectionManager`), so connections to an
SDLC system are kept alive and reused. The pool is sized with `resourceapi.http-client.pool.*`
(`max-total` 50, `max-per-route` 10, `connection-request-timeout` 2s, `idle-timeout` 30s, `time-to-live` 5m);
see `HttpClientPoolProperties` and `HttpClientPoolConfig`. Every `RestClient.Builder` the application
injects uses Boot's single request factory, so there is one pool for the application, closed on shutdown.

The client is tested against [WireMock](https://wiremock.org/) (`SdlcProjectClientTest`, `ExternalProjectIntegrationTest`,
and `SdlcProjectClientPoolingTest`, which counts the connections actually opened).

## API documentation
The OpenAPI document is served at `/v3/api-docs` and Swagger UI at `/swagger-ui.html`
([springdoc-openapi](https://springdoc.org/)), generated from the controllers and their `@Operation`/`@Parameter`
annotations. Only `/api/**` is documented.
To turn both off (e.g. in production), set `springdoc.api-docs.enabled=false` and `springdoc.swagger-ui.enabled=false`.

## Redis
The application uses Redis (`spring.data.redis.host`/`port`, default `localhost:6379`).

**Cache** (Spring Cache on Spring Data Redis, values stored as JSON):

| Cache | What | TTL |
|---|---|---|
| `sdlc-systems` | SDLC systems by id, read on every create, update and SDLC call | `resourceapi.cache.sdlc-systems-ttl` (10m) |
| `external-projects` | `GET /api/v2/projects/{id}/external` answers per SDLC system and external id | `resourceapi.cache.external-projects-ttl` (5m); "the SDLC system doesn't know this project" (stored as a cached null) only `resourceapi.cache.missing-external-projects-ttl` (30s), so a project created there is found soon |

A background sync always asks the SDLC system and replaces the cached answer with its own. SDLC systems are not changed
through this API; one changed directly in the database is picked up when its entry expires. The cache is an
optimization: if Redis is unreachable, cache reads and writes are logged and skipped (each waits at most the
500 ms Redis timeouts) and requests are served without it.

**Lock** ([Redisson](https://redisson.org/) `RLock`): at most one `POST /api/v2/projects/{id}/sync` per project
runs at a time, across all instances; a second one gets `409 Conflict` until the first ends. The lock is
released when the background sync ends and expires after `resourceapi.sync.lock-lease-time` (60s) in case
an instance dies mid-sync. If Redis can't be reached, the sync runs without the lock (a duplicate sync only
costs an extra SDLC call; the failed lock request takes about a second). Redisson connects lazily and uses the
same Redis connection settings; its keys are prefixed with `resourceapi.redisson.key-prefix`. Only a
standalone Redis is supported for the lock: with Sentinel or Cluster the application still starts and syncs
run unlocked; TLS uses the JVM's trust store (Boot SSL bundles aren't passed to Redisson). (Redisson's Spring Boot starter isn't used: it targets
Spring Boot 3. Redisson brings a JCache provider, so `spring.cache.type=redis` is set explicitly.)

**Rate limits** (Redisson `RRateLimiter`, sliding windows shared by all instances: a used permit becomes
available again one period later, which is also what `Retry-After` says):

| Limit | Default | When exceeded |
|---|---|---|
| Calls to one SDLC system (`resourceapi.rate-limit.sdlc.*`), protecting its API quota; cache hits don't count | 10 per 1s, waiting up to 1s for a permit | `503` + `Retry-After` (our own limit, not the SDLC system's failure); a background sync logs it |
| `POST /api/v2/projects/{id}/sync` per project (`resourceapi.rate-limit.sync.*`); requests refused anyway (unknown project, sync already running) don't count, one refused because no background slot is free (`503`) does | 6 per minute | `429` + `Retry-After` |
| All `/api/**` requests per client IP (`resourceapi.rate-limit.client.*`); behind a reverse proxy the IP comes from `X-Forwarded-For`, trusted only from internal proxy addresses (`server.forward-headers-strategy=native`, see below) | 100 per second | `429` + `Retry-After` |

A changed rate is applied to the existing limiter when an instance first uses it; unused limiters expire from
Redis after 10 periods (at least a minute), so per-IP limiters don't pile up. Invalid limits (e.g.
`permits=0`) stop the application at startup. If Redis can't be reached, requests are allowed (limiting
protects, losing it shouldn't stop the application), and Redis is not asked again for 10 seconds, so an
outage doesn't add a timeout to every request.

The per-IP limit only means something if the application sees the client's address. Tomcat takes it from
`X-Forwarded-For` when the request comes from an address matching `server.tomcat.remoteip.internal-proxies`
(by default 10/8, 192.168/16, 172.16/12, 169.254/16, 127/8 and IPv6 local addresses). If the reverse proxy or
load balancer has another address, set that property to match it; otherwise every request appears to come from
the proxy and all clients share one limit. Never match addresses clients can connect from directly, or they
can choose their own key by sending `X-Forwarded-For`.

**Local development**: `./mvnw spring-boot:run` starts Redis from `compose.yaml` automatically
(`spring-boot-docker-compose`); with `java -jar`, start it yourself with `docker compose up -d` (it listens on
`localhost:6379`). Tests start their own Redis with Testcontainers, so they need Docker.

## Tracing
Every request gets a trace id ([Micrometer Tracing](https://micrometer.io/docs/tracing) with Brave, W3C trace context):

- an incoming `traceparent` (or B3) header is continued, otherwise a new trace starts
- the trace id is returned in the `X-Trace-Id` response header, also on errors
- log lines carry `[traceId-spanId]`, so a reported `X-Trace-Id` leads straight to its log lines
- outgoing `RestClient` calls (e.g. to SDLC systems) send `traceparent`, so the trace continues there

- background work keeps the trace: `@Async` tasks run on virtual threads (`spring.threads.virtual.enabled`) and
  `ContextPropagatingTaskDecorator` copies the caller's trace context to them. Example:
  `POST /api/v2/projects/{id}/sync` answers `202 Accepted` and refreshes the project's name from its SDLC system
  in the background; its log lines and its call to the SDLC system carry the request's trace id. At most
  `spring.task.execution.simple.concurrency-limit` (8) syncs run at once; further requests get `503` with `Retry-After`

No spans are exported; add a reporter (e.g. Zipkin or OTLP) to send them to a tracing backend, and lower
`management.tracing.sampling.probability` (set to `1.0` here) to what that backend can take.

## Testing
There are scripts to test the Rest API in Postman.