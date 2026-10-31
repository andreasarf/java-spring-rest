# java-spring-rest
![CI](https://github.com/andreasarf/java-spring-rest/actions/workflows/ci.yml/badge.svg?branch=main)
![Coverage](.github/badges/jacoco.svg)
![Branches](.github/badges/branches.svg)

Example for implementation Rest API using Spring Boot.

## Requirement
Java 25+ (Spring Boot 4)

## Build
`./mvnw clean install`

## Native image (GraalVM)
The JVM build (`./mvnw clean install`) is the main build and the only one CI runs. A native executable is an
optional alternative and is built manually. The application can be compiled ahead of time into a native executable with
[GraalVM](https://www.graalvm.org/) (GraalVM CE for JDK 25, with `native-image`, plus gcc and zlib headers):

```
export JAVA_HOME=/path/to/graalvm-jdk-25
./mvnw -Pnative -DskipTests native:compile
./target/resourceapi
```

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

## Testing
There are scripts to test the Rest API in Postman.