# Docker images

Three ways to package the application, from the simplest to the fastest-starting. All are multi-stage
builds: the application is compiled inside Docker, and the final image contains only what is needed to
run it, running as a non-root user on port 8080. Tests are not run during the image build (CI runs them).

| | `Dockerfile` | `Dockerfile.aot` | `Dockerfile.native` |
|---|---|---|---|
| What runs | Spring Boot jar on a JRE | Spring Boot jar on a JRE, with Spring AOT and a Java 25 AOT cache | Native executable, no JVM |
| Base images | `eclipse-temurin:25-jdk` → `eclipse-temurin:25-jre` | same | `ghcr.io/graalvm/native-image-community:25` → `debian:bookworm-slim` |
| Image size | ~600 MB | ~750 MB | (being measured) |
| Startup | ~5.4 s | ~1.4 s | (being measured) |
| Memory after start | ~300 MB | ~320 MB | (being measured) |
| Build | ~1 min after dependencies are cached | ~1 min | (being measured), needs ~10 GB memory |
| Configuration at runtime | everything | values only (see below) | values only (see below) |

Measured on a 4-core / 16 GB machine; startup is Spring Boot's own "Started ... in" time.

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

The cache only fits the exact JVM and class path it was created with, which is why it is created on the
runtime image itself. If it can't be used, the JVM ignores it and starts normally (just slower). A few
warnings like `class ... cannot be archived` during the build are expected: those classes come from the
Java-version-specific part of a multi-release jar and are loaded normally.

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
