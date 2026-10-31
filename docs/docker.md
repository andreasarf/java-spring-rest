# Docker images

Three ways to package the application, from the simplest to the fastest-starting. All are multi-stage
builds: the application is compiled inside Docker, and the final image contains only what is needed to
run it, running as a non-root user on port 8080. Tests are not run during the image build (CI runs them).

| | `Dockerfile` | `Dockerfile.aot` | `Dockerfile.native` |
|---|---|---|---|
| What runs | Spring Boot jar on a JRE | Spring Boot jar on a JRE, with Spring AOT and a Java 25 AOT cache | Native executable, no JVM |
| Base images | `eclipse-temurin:25-jdk` → `eclipse-temurin:25-jre` | same | `ghcr.io/graalvm/native-image-community:25` → `debian:bookworm-slim` |
| Image size | ~600 MB | ~750 MB (incl. the 125 MB AOT cache) | ~400 MB |
| Startup | ~5.4 s | ~1.4 s | ~0.27 s |
| Memory after start | ~300 MB | ~320 MB | ~90 MB |
| Build | ~1 min after dependencies are cached | ~1 min | ~6 min, needs ~10 GB memory |
| Configuration at runtime | everything | values only (see below) | values only (see below) |

Measured on a 4-core / 16 GB machine: startup is Spring Boot's own "Started ... in" time (median of three),
memory is `docker stats` right after startup, image size as reported by `docker images`.

## Standard: `Dockerfile`

```sh
docker build -t resourceapi .
docker run --rm -p 8080:8080 resourceapi
```

The Maven wrapper builds the jar on a JDK image; dependencies are downloaded in their own layer (and into a
BuildKit cache), so changing only the sources doesn't download them again. The jar is split into Spring
Boot's layers (dependencies, loader, application) and run with `java -jar` on a JRE image.

## Faster JVM startup: `Dockerfile.aot`

```sh
docker build -f Dockerfile.aot -t resourceapi:aot .
docker run --rm -p 8080:8080 resourceapi:aot
```

Still the JVM and the same jar, plus two ahead-of-time optimizations:

- **Spring AOT** (`spring-boot:process-aot`): the bean wiring is generated as code at build time and used at
  runtime with `-Dspring.aot.enabled=true`, so Spring doesn't have to work it out on every start.
- **Java 25 AOT cache** ([JEP 483](https://openjdk.org/jeps/483), [514](https://openjdk.org/jeps/514),
  [515](https://openjdk.org/jeps/515)): during the image build the application is started once (a training
  run that stops as soon as the context is ready, `-Dspring.context.exit=onRefresh`) with
  `-XX:AOTCacheOutput`. The JVM records the classes it loaded and linked and their method profiles in
  `application.aot`; containers start with `-XX:AOTCache=application.aot` and skip most of that work.
  Most of the speed-up comes from the cache (Spring AOT alone starts in about 4.3 s).

No application properties are needed, only these JVM flags, which the image already sets:

| Flag | When | Purpose |
|---|---|---|
| `-Dspring.aot.enabled=true` | runtime (`ENTRYPOINT`) | use Spring's generated code. Must be a JVM system property: `SPRING_AOT_ENABLED` as an environment variable or `application.properties` is **not** read, and without it Spring silently starts the normal way |
| `-XX:AOTCache=application.aot` | runtime (`ENTRYPOINT`) | start from the AOT cache |
| `-XX:AOTCacheOutput=application.aot` | image build (training run) | write the AOT cache |
| `-Dspring.context.exit=onRefresh` | image build (training run) | stop as soon as the application has started |

If you override the entrypoint, keep the two runtime flags; add other JVM options through `JAVA_TOOL_OPTIONS`.
Heap limits (`-Xmx`, `docker run -m`) and a different garbage collector still use the cache (checked with
`-XX:AOTMode=on`, which refuses to start when the cache can't be used). Because the training run starts the
application during `docker build`, everything it needs at startup must be available then; this application
only needs its in-memory H2 database.

The cache only fits the exact JVM and class path it was created with, which is why it is created on the
runtime image itself. If it can't be used, the JVM ignores it and starts normally (just slower). A few
warnings like `class ... cannot be archived` during the build are expected: those classes come from the
Java-version-specific part of a multi-release jar and are loaded normally.

### Training an application that needs external services

The training run is a real startup inside `docker build`: it runs the whole context refresh, including
starting lifecycle beans, and only then exits. An application with a database, Redis or RabbitMQ
therefore connects to them during the image build, where they usually don't exist. Typically:

- **Database:** Hibernate connects at boot to read database metadata; Flyway/Liquibase run migrations.
- **RabbitMQ:** listener containers start and connect; `RabbitAdmin` declares queues and exchanges.
- **Redis:** mostly connects lazily, but health checks, Spring Session or cache warm-up can touch it.

There are two ways to handle this.

**1. A `training` profile with value-only settings.** Activate it on the training command only:

```dockerfile
RUN java -XX:AOTCacheOutput=application.aot -Dspring.aot.enabled=true -Dspring.profiles.active=training \
        -Dspring.context.exit=onRefresh -jar application.jar
```

```properties
# application-training.properties (example for PostgreSQL + RabbitMQ)
# Hibernate: don't connect at boot to detect the database
spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
# Hikari: don't fail when no connection can be made at startup
spring.datasource.hikari.initialization-fail-timeout=-1
# RabbitMQ listeners: created, but not started, so they don't connect
spring.rabbitmq.listener.simple.auto-startup=false
```

The settings must change **values**, not switch beans on or off. With `-Dspring.aot.enabled=true` the
bean decisions were made when the image was built, so a property that only appears in a condition (for
example `spring.flyway.enabled=false` or `springdoc.api-docs.enabled=false`) is silently ignored. Profile
property files themselves are still read. Both were checked on this image: `-Dspringdoc.api-docs.enabled=false`
left `/v3/api-docs` up, while a value from an `application-training.properties` was applied. Migrations
can't be skipped this way; they need a reachable database or a migration step outside the application.
(The example settings above are a pattern for such applications; this application doesn't need them.)

**2. Train against real services.** Run the training where the database, Redis and RabbitMQ are
reachable, for example `docker compose up` for them plus `docker build --network host`, or a CI job with
service containers. This gives the better cache: it only covers the classes the training run loaded, so
every feature switched off for training (JDBC driver, Flyway, AMQP client, ...) is missing from the cache
and loads the slow way in production.

Either way, compare startup with and without `-XX:AOTCache` to see what the training gives you.

## Native executable: `Dockerfile.native`

```sh
docker build -f Dockerfile.native -t resourceapi:native .
docker run --rm -p 8080:8080 resourceapi:native
```

Builds the GraalVM native executable (see the [native image guide](native-image.md)) in GraalVM's
`native-image-community` image and runs it on `debian:bookworm-slim`. Give Docker about 10 GB of memory for
the build. The executable is built for the architecture of the machine (or `--platform`) that builds it.

## Configuration

All three images are configured like the application itself, e.g. with environment variables:

```sh
docker run --rm -p 9090:9090 -e SERVER_PORT=9090 resourceapi
docker run --rm -p 8080:8080 -e SPRING_HTTP_CLIENTS_READ_TIMEOUT=10s resourceapi:aot
```

With `Dockerfile.aot` and `Dockerfile.native`, Spring AOT decides at build time which beans exist, so
settings that switch beans on or off (for example `springdoc.api-docs.enabled` or the active profile) only
take effect when the image is built; see
[configuration fixed at build time](native-image.md#configuration-fixed-at-build-time).
