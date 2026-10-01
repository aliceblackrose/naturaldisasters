# Paper Plugin Template

Minimal Java 25 template for Paper 26.1 plugins. It keeps only the pieces needed to compile, test, lint, format, run, and package a plugin.

## Commands

```bash
./gradlew build          # compile, test, PMD, formatting checks, package
./gradlew spotlessApply # format Java sources
./gradlew runServer     # start a local Paper server
```

## Use this template

1. Change `rootProject.name` in `settings.gradle`.
2. Change `group` and `version` in `build.gradle`.
3. Rename `io.github.aliceblackrose.template.TemplatePlugin` and its package.
4. Update `src/main/resources/plugin.yml` to match the new entry point and metadata.
5. Add only the configuration, dependencies, and tests required by real behavior.

## Code policy

The build treats compiler warnings as errors, runs PMD static analysis, and enforces Google Java Format through Spotless. The rules are intended to cover the enforceable parts of the JPL Java coding guidance: explicit and simple code, static analysis, documented public code, unit testing, defensive error handling, and low complexity.

There are deliberately no placeholder services, sample configuration files, architecture layers, or fake tests in the template.

## License

GPL-3.0. See `LICENSE`.
