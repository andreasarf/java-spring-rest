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

## Conditional requests (ETag)
`GET`, `POST` and `PATCH` on `/api/v2/projects/{id}` return the project's `ETag`, a hash of what a client can
change (external id, name, SDLC system):

- `GET` with `If-None-Match: <etag>` answers `304 Not Modified` without a body while the project is unchanged,
  so a client can re-check a project cheaply.
- `PATCH` with `If-Match: <etag>` applies only if nobody changed the project since the client read it;
  otherwise `412 Precondition Failed` (read it again, reapply the change). The project's row is locked from the
  check until the change is committed (`SELECT ... FOR UPDATE`), so nothing can slip in between. `If-Match: *`
  matches any existing project; a weak `W/` tag never matches. Without `If-Match` an update always applies, as
  before.

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

## Events
`ProjectService` publishes Spring application events (package `events`) inside the transaction that
changes a project:

- `ProjectCreated`
- `ProjectUpdated`, with the new state and which fields changed (`NAME`, `EXTERNAL_ID`, `SDLC_SYSTEM`); not
  published when an update changes nothing. Changes made by a sync from the SDLC system are published too.

Both carry `occurredAt`, when the change was made: events about one project can reach a listener out of order
(e.g. one retried later), so an event older than the last one applied is stale.

Listeners use `@TransactionalEventListener`, so they only see committed changes. `SyncOnProjectChange`
starts a background sync (as `POST /api/v2/projects/{id}/sync` does) when a project is created or re-keyed
(pointed at another SDLC project), so its name comes from the SDLC system without the client asking. A sync
that can't start right then (one is running, rate limited, RabbitMQ unreachable) is logged and skipped.
The listener runs on the request thread after commit; it only queues the sync, but with Redis or RabbitMQ
unreachable the failed attempt adds about a second (Redis) or up to `spring.rabbitmq.connection-timeout` (2s)
to the request.
Turn it off with `resourceapi.sync.on-change=false` (tests do, except `SyncOnProjectChangeTest`).

## Sync queue (RabbitMQ)
`POST /api/v2/projects/{id}/sync` queues the sync in the durable RabbitMQ queue `resourceapi.project-sync`
(`resourceapi.sync.queue`) and answers `202 Accepted` once the broker confirmed it has the request; a consumer
(`ProjectSyncConsumer`) refreshes the project's name from its SDLC system. Requests survive restarts, and a burst
of them waits in the queue instead of being refused. If RabbitMQ is unreachable, the request gets `503` with
`Retry-After`.

- Each instance runs `spring.rabbitmq.listener.simple.concurrency` (4) consumers on virtual threads, taking one
  request at a time (`prefetch=1`), so waiting requests go to whichever instance is free.
- A sync failing for a reason that may pass (SDLC system unreachable, `5xx` or `429`, our own SDLC rate limit) is
  retried after 1s and 2s (`resourceapi.sync.max-retries`, `retry-delay`, `retry-multiplier`); a sync that still
  fails, or fails for another reason (e.g. `403`), goes to the dead-letter queue `<queue>.dead`, where it can be
  inspected and moved back (e.g. with the management UI's shovel). A project deleted in the meantime is skipped.
- The project's sync lock (see Redis below) is released however the sync ends.

## Notifications (RabbitMQ)
Other systems learn about project changes from RabbitMQ (`spring.rabbitmq.*`, default `localhost:5672`):
`ProjectCreated` and `ProjectUpdated` are sent as JSON to the topic exchange `resourceapi.projects`
(`resourceapi.notifications.exchange`) with routing keys `project.created` and `project.updated`. Messages carry
the request's trace (`traceparent` header).

No notification is lost, even if RabbitMQ is down or the application stops right after a change (an outbox):

1. Spring Modulith's event publication registry stores each event in the `event_publication` table, in the
   transaction that changed the project: it exists exactly if the change was committed.
2. After commit, `ProjectNotifier` sends it in the background and waits for the broker's confirmation (publisher
   confirms). Only then is the row deleted. Sends run on their own virtual-thread executor, at most 16 at once;
   when all are busy (e.g. waiting for an unreachable broker) a further send is refused instead of holding up the
   request (Spring logs the `TaskRejectedException`), and its event stays in the table.
3. A send that failed or wasn't confirmed leaves the row; `UnsentNotifications` sends such events again
   (`resourceapi.notifications.resubmit-interval` 1m, for events older than `resubmit-after` 1m). With several
   instances, one does it at a time (Redis lock).

Notifications about one project can arrive out of order (they are sent in parallel, and a resent one arrives
late): consumers should use `occurredAt` and ignore one older than the last they applied. Delivery is at least
once: a consumer may see the same notification twice (e.g. the broker confirmed
but the application stopped before deleting the row) and should handle that, e.g. by treating them as "the
project now looks like this". Modulith's own RabbitMQ externalization isn't used: in its default mode it doesn't
wait for the broker's confirmation, so a message the broker refused would count as sent.

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
is queued or running at a time, across all instances; a second one gets `409 Conflict` until the first ends. The
lock is taken by the request and released by whichever instance runs the sync when it ends (the request carries
the lock's owner id), and expires after `resourceapi.sync.lock-lease-time` (60s) in case an instance dies
mid-sync or the request waits in the queue longer than that. If Redis can't be reached, the sync runs without the lock (a duplicate sync only
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
| `POST /api/v2/projects/{id}/sync` per project (`resourceapi.rate-limit.sync.*`); requests refused anyway (unknown project, sync already running) don't count, one refused because RabbitMQ is unreachable (`503`) does | 6 per minute | `429` + `Retry-After` |
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

**Local development**: `./mvnw spring-boot:run` starts Redis and RabbitMQ from `compose.yaml` automatically
(`spring-boot-docker-compose`); with `java -jar`, start them yourself with `docker compose up -d` (Redis on
`localhost:6379`, RabbitMQ on `localhost:5672`, its management UI on http://localhost:15672, guest/guest).
Tests start their own Redis and RabbitMQ with Testcontainers, so they need Docker.

## Tracing
Every request gets a trace id ([Micrometer Tracing](https://micrometer.io/docs/tracing) with Brave, W3C trace context):

- an incoming `traceparent` (or B3) header is continued, otherwise a new trace starts
- the trace id is returned in the `X-Trace-Id` response header, also on errors
- log lines carry `[traceId-spanId]`, so a reported `X-Trace-Id` leads straight to its log lines
- outgoing `RestClient` calls (e.g. to SDLC systems) send `traceparent`, so the trace continues there

- background work keeps the trace: `@Async` tasks run on virtual threads (`spring.threads.virtual.enabled`) and
  `ContextPropagatingTaskDecorator` copies the caller's trace context to them; RabbitMQ messages carry it in a
  `traceparent` header (Spring AMQP observation) and their listeners continue it. Example: a queued sync's log
  lines and its call to the SDLC system carry the trace id of the `POST …/sync` request

No spans are exported; add a reporter (e.g. Zipkin or OTLP) to send them to a tracing backend, and lower
`management.tracing.sampling.probability` (set to `1.0` here) to what that backend can take.

## Testing
There are scripts to test the Rest API in Postman.