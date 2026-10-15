# java-spring-rest
![CI](https://github.com/andreasarf/java-spring-rest/actions/workflows/ci.yml/badge.svg?branch=main)
![Coverage](.github/badges/jacoco.svg)
![Branches](.github/badges/branches.svg)

Example for implementation Rest API using Spring Boot.

## Requirement
Java 25+ (Spring Boot 4)

## Build
`./mvnw clean install`

## Database
Database uses in-memory DB H2. It will automatically build and export the required tables and data.

The H2 console (`/h2-console`, JDBC URL `jdbc:h2:mem:test`) is available while running with devtools, e.g. `./mvnw spring-boot:run`. It is not served by the packaged jar.

## Testing
There are scripts to test the Rest API in Postman.