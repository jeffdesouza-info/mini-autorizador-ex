# Repository Guidelines

## Project Structure & Module Organization

This repository is a Java 21 Spring Boot API built with Maven. Production code belongs in `src/main/java/br/com/miniautorizador`; the current package placeholders are `cartao`, `idempotencia`, `transacao`, and `shared`. Keep new code grouped by domain, and use `shared` only for genuinely cross-domain concerns. Application resources and Flyway migrations belong in `src/main/resources` and `src/main/resources/db/migration`. Tests mirror production packages under `src/test/java`; test resources go in `src/test/resources`. Local database services are defined in `docker/docker-compose.yml`.

## Build, Test, and Development Commands

- `.\mvnw.cmd test` compiles the project and runs the Maven test suite.
- `.\mvnw.cmd package` creates the application package in `target/`.
- `docker compose -f docker/docker-compose.yml up -d mysql` starts the local MySQL service.
- `.\mvnw.cmd spring-boot:run` starts the API once its Spring Boot entry point and datasource configuration are present.

Use the Maven Wrapper so contributors share the Maven version selected by the repository.

## Coding Style & Naming Conventions

Use Java 21 and four spaces for indentation; avoid tabs. Keep package names lowercase, types in `UpperCamelCase`, methods and fields in `lowerCamelCase`, and constants in `UPPER_SNAKE_CASE`. Follow the existing domain package boundaries. No formatter or linter is configured in `pom.xml`, so keep formatting consistent with nearby code and remove unused imports.

## Testing Guidelines

Tests use the Spring Boot test dependency and run through Maven. Name test classes `*Test.java`, mirror the production package, and cover authorization decisions, idempotency, transaction behavior, and persistence changes. Add or update tests with behavior changes. No coverage threshold is configured.

## Database and Configuration

Add schema changes as ordered Flyway migrations in `src/main/resources/db/migration`, using names such as `V1__create_cards.sql`. Keep credentials and environment-specific settings out of committed source; use local configuration for development.

## Commits and Pull Requests

Recent history uses a parenthesized type tag, for example `(chore) : scaffold/estrutura inicial para o projeto.` Follow that concise pattern with a suitable type such as `(feat)` or `(fix)`. Pull requests should explain the change, list validation commands and results, and call out API, migration, or configuration impacts; link a related issue when one exists.
