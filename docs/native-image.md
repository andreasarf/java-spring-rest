# Native image guide

The JVM build (`./mvnw clean install`, run with `java -jar`) is the main build and the only one CI runs.
A GraalVM native executable is an optional alternative: it starts in well under a second and needs no
JVM, at the cost of a slow, memory-hungry build. This guide covers building and running it.

## 1. Prerequisites

| | |
|---|---|
| GraalVM | GraalVM CE (or Oracle GraalVM) **for JDK 25**, which includes `native-image` |
| C toolchain | `gcc` and the zlib headers (Debian/Ubuntu: `sudo apt install build-essential zlib1g-dev`; macOS: Xcode Command Line Tools) |
| Memory | about 10 GB free for the build (see [Build memory](#build-memory)) |

Install GraalVM, for example from the
[GraalVM CE releases](https://github.com/graalvm/graalvm-ce-builds/releases) (`graalvm-community-jdk-25.x.x_<os>-<arch>_bin`),
and point `JAVA_HOME` at it:

```sh
export JAVA_HOME=/opt/graalvm-25     # wherever you unpacked it
export PATH="$JAVA_HOME/bin:$PATH"
native-image --version               # native-image 25.x ...
```

A native executable only runs on the operating system and CPU architecture it was built on (there is no
cross-compiling): build on Linux x86-64 to get a Linux x86-64 binary.

## 2. Build

```sh
./mvnw -Pnative -DskipTests native:compile
```

Spring Boot's `native` profile first runs AOT processing (it evaluates the application's configuration and
generates the bean wiring plus reflection/resource hints as code), then the `native-maven-plugin` runs
`native-image`. The result is a single file:

```
target/resourceapi      # about 200 MB, statically contains the app, its libraries and a small runtime
```

On a 4-core / 16 GB machine the build takes 5–7 minutes. `-DskipTests` skips the JVM tests, which the
normal build runs anyway.

### Build memory

`native-image` analyses every class reachable from the application (here about 44,000 classes and 218,000
methods) and compiles all of them ahead of time, so it peaks at about 9–10 GB. By default it may use up to
75% of the machine's memory. To trade build time for memory, add build arguments to the
`native-maven-plugin` in `pom.xml`:

```xml
<plugin>
    <groupId>org.graalvm.buildtools</groupId>
    <artifactId>native-maven-plugin</artifactId>
    <configuration>
        <buildArgs>
            <buildArg>-J-Xmx6g</buildArg>        <!-- cap the build's heap -->
            <buildArg>--parallelism=2</buildArg> <!-- fewer compiler threads, lower peak -->
            <buildArg>-Ob</buildArg>             <!-- quick build: less optimization, for local testing -->
        </buildArgs>
    </configuration>
</plugin>
```

## 3. Run

```sh
./target/resourceapi
```

That's all: no `java`, no JVM on the machine. The application starts in about 0.3 s and listens on port 8080
as usual (`http://localhost:8080/api/v2/projects/1`, Swagger UI at `/swagger-ui.html`). Stop it with
`Ctrl+C` or `SIGTERM`; it shuts down gracefully, giving running background syncs up to 10 s to finish.

### Configuration at runtime

Settings that only change a *value* work exactly as on the JVM:

```sh
./target/resourceapi --server.port=9090                     # command-line argument
SERVER_PORT=9090 ./target/resourceapi                       # environment variable
./target/resourceapi --spring.http.clients.read-timeout=10s
./target/resourceapi -Xmx128m                               # cap the heap, like on the JVM
```

An `application.properties` in a `config/` directory next to where you start it (or one given with
`--spring.config.additional-location=...`) is picked up too.

### Configuration fixed at build time

AOT processing decides at **build time** which beans exist. Settings that switch beans on or off, or that
choose between implementations, are therefore frozen into the executable and are **ignored at runtime**.
For example, `./target/resourceapi --springdoc.api-docs.enabled=false` still serves `/v3/api-docs` (the same
flag works with `java -jar`).

In this application that applies to, among others:

- `springdoc.api-docs.enabled`, `springdoc.swagger-ui.enabled` (OpenAPI document and Swagger UI)
- `spring.profiles.active` (which profile-specific beans and configuration exist)
- `spring.threads.virtual.enabled`, `spring.http.clients.imperative.factory`, `management.tracing.enabled`

Set such properties when building, through the AOT step's JVM arguments:

```sh
./mvnw -Pnative -DskipTests native:compile \
  -Dspring-boot.aot.jvmArguments="-Dspringdoc.api-docs.enabled=false -Dspringdoc.swagger-ui.enabled=false"

# or build for a profile
./mvnw -Pnative -DskipTests native:compile -Dspring-boot.aot.jvmArguments="-Dspring.profiles.active=prod"
```

Rule of thumb: if a property appears in a `@ConditionalOn...`/`@Profile` condition, set it at build time;
if it is just a value a bean reads (ports, URLs, timeouts, pool sizes, sampling probability), set it at
runtime.

## 4. Troubleshooting

- **`MissingReflectionRegistrationError`, `ClassNotFoundException` or a missing resource at runtime**: some
  code reaches a class or file in a way the build couldn't see (reflection by name, `Class.forName`,
  resources loaded by a computed path). Register it with Spring's `RuntimeHints`
  (`@ImportRuntimeHints`), or find what is missing by running the JVM app with GraalVM's tracing agent
  (`java -agentlib:native-image-agent=config-output-dir=... -jar target/*.jar`) and exercising it.
- **The build is killed or very slow**: it ran out of memory; see [Build memory](#build-memory).
- **"version `GLIBC_x.y' not found" on another machine**: the executable links against the build machine's
  C library; build on (or in a container matching) the oldest system it has to run on.
