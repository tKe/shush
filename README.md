# _shush_

[![CI](https://github.com/tKe/shush/actions/workflows/ci.yml/badge.svg)](https://github.com/tKe/shush/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.tke/shush-compiler-plugin)](https://central.sonatype.com/artifact/io.github.tke/shush-compiler-plugin)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.tke.shush)](https://plugins.gradle.org/plugin/io.github.tke.shush)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

A K2 compiler plugin, with a Gradle plugin, that suppresses chosen compiler warnings in chosen places: inside
[Mokkery](https://mokkery.dev) templates, in generated sources, in lambdas passed to particular functions, or in
declarations with a particular annotation.

## Why

Some warnings are right in general but noise in places you can't or shouldn't edit to add `@Suppress`.

With `-Xreturn-value-checker=check` or `=full`, Kotlin reports `RETURN_VALUE_NOT_USED` /
`RETURN_VALUE_NOT_USED_COERCION` for every discarded result. Mokkery templates are made of such calls, recorded
rather than run, so their results are never meant to be used:

```kotlin
verify {
    repository.find(1)   // w: Unused return value of 'find'.
    repository.save("a") // w: Unused return value of 'save'.
}
```

With `allWarningsAsErrors` they fail the build.

Generated code (protobuf, OpenAPI, KSP, ...) has the same problem with `extraWarnings` (`-Wextra`) or the return
value checker: explicit `public` modifiers, discarded results and unused variables you can't fix in the source.

## How it works

FIR has no diagnostic suppressor extension point, but its checkers report through a reporter that drops anything
covered by a `@Suppress` on an enclosing file, declaration, lambda or block. _shush_ adds that `@Suppress` before
the checkers run:

- for `calls` and `annotatedCalls`, a `FirExpressionResolutionExtension` puts it on each matching call once it's
  resolved, as `@Suppress("...") f()` would;
- for `callLambdas`, `annotatedParameterLambdas` and the Mokkery preset, the same extension puts it on the body of each
  matching lambda argument;
- for `files` and `annotatedDeclarations`, a `FirStatusTransformerExtension`, used only as a hook in the STATUS
  phase, puts `@file:Suppress` on matching files and `@Suppress` on matching declarations.

`@Suppress` isn't repeatable, so if one is already there (such as a generated `@file:Suppress("UNUSED_PARAMETER")`) the
names are merged into it. Generated code and runtime behaviour don't change.

## Usage (Gradle)

The Gradle plugin is on the Gradle Plugin Portal as `io.github.tke.shush`. The Kotlin Gradle plugin resolves the
compiler plugin it adds, `io.github.tke:shush-compiler-plugin`, from the project repositories, so they need Maven
Central. `<version>` below is the latest release, shown by the badges above.

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
```

`build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.4.21"
    id("dev.mokkery") version "3.5.1"
    id("io.github.tke.shush") version "<version>"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xreturn-value-checker=full")
        extraWarnings = true
    }
}

shush {
    // The Mokkery preset is enabled automatically when the dev.mokkery plugin is applied.
    // mokkery()              // enable it explicitly
    // mokkery.set(false)     // or opt out

    rule("generated") {
        diagnostics.add("REDUNDANT_VISIBILITY_MODIFIER")
        diagnostics.add("RETURN_VALUE_NOT_USED")
        files.add("build/generated/**")
    }
    rule("templates") {
        diagnostics.add("RETURN_VALUE_NOT_USED")
        diagnostics.add("RETURN_VALUE_NOT_USED_COERCION")
        callLambdas.add("com.example.myTemplate")
        annotatedCalls.add("com.example.Template")
    }
    rule { // anonymous
        diagnostics.add("REDUNDANT_VISIBILITY_MODIFIER")
        annotatedDeclarations.add("com.example.Legacy")
    }
}
```

The plugin applies to every Kotlin compilation (main, test, test fixtures, ...) and works with the configuration
cache.

## DSL reference

| `shush { ... }`         | Type                                   | Meaning                                                                                   |
|-------------------------|----------------------------------------|-------------------------------------------------------------------------------------------|
| `mokkery`               | `Property<Boolean>`                    | Enable the `mokkery` preset. Convention: `true` iff the `dev.mokkery` plugin is applied    |
| `mokkery()`             | function                               | Same as `mokkery.set(true)`                                                               |
| `rules`                 | `NamedDomainObjectContainer<ShushRuleSpec>` | Custom rules                                                                         |
| `rule(name) { ... }`    | function                               | Create or configure the rule `name`                                                       |
| `rule { ... }`          | function                               | Create an anonymous rule                                                                  |
| `anonymousRules`        | `DomainObjectSet<ShushRuleSpec>`       | Anonymous rules                                                                           |

An anonymous rule is named `anonymous-<hash>` after its contents, so declaration order doesn't change task inputs and
identical rules collapse into one. Errors describe it by its first target instead, as in
`anonymous shush rule (files: build/generated/**) in project ':app'`. Use a named rule if it needs extending from
several places, such as a convention plugin and a build script. Named rules can't start with `anonymous-`.

Each rule (`ShushRuleSpec`) has these `SetProperty<String>`s, and suppresses its `diagnostics` wherever any of its
targets match:

| Property                    | Values              | Suppresses within                                                                        |
|-----------------------------|---------------------|------------------------------------------------------------------------------------------|
| `diagnostics`               | diagnostic names    | what to suppress; required if the rule has targets                                       |
| `files`                     | globs               | source files matching the glob, relative to the project directory (`build/generated/**`) |
| `calls`                     | `fq.function.name`  | calls of the function, including their receiver and arguments                            |
| `annotatedCalls`            | `fq.AnnotationName` | calls of functions annotated with it, including their receiver and arguments             |
| `callLambdas`               | `fq.function.name`  | lambdas passed to calls of the function                                                  |
| `annotatedParameterLambdas` | `fq.AnnotationName` | lambdas passed to parameters annotated with it, or whose type is annotated with it       |
| `annotatedDeclarations`     | `fq.AnnotationName` | declarations annotated with it, including everything inside them                         |

`calls` and `annotatedCalls` cover the whole call, lambdas included. `callLambdas` and `annotatedParameterLambdas`
cover only the lambdas (including ones passed as Java or `fun` interfaces), so the call's own result and its other
arguments are still checked.

`calls` suits generated code you can't annotate. A gRPC method returning `com.google.protobuf.Empty` can't be marked
`@IgnorableReturnValue`, but its calls can be exempted:

```kotlin
rule("empty-responses") {
    diagnostics.add("RETURN_VALUE_NOT_USED")
    diagnostics.add("RETURN_VALUE_NOT_USED_COERCION")
    calls.add("com.example.FooServiceGrpcKt.FooServiceCoroutineStub.ping")
}
```

Members are named by their class's fully-qualified name, so a nested class is `com.example.Outer.Inner.method`. All
overloads of the function match.

The diagnostic name `warnings` (lowercase) is the compiler's own catch-all, as in `@Suppress("warnings")`, and
suppresses every warning but no errors:

```kotlin
rule("generated") {
    diagnostics.add("warnings")
    files.add("build/generated/**")
}
```

Nested annotation classes can be given as class ids, like `com/example/Outer.Inner`. Values can't contain commas,
because the compiler splits plugin options on them, so use two globs rather than `{a,b}`.

### Presets

| Preset    | Diagnostics                                                | Targets                                                                                                                       |
|-----------|------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| `mokkery` | `RETURN_VALUE_NOT_USED`, `RETURN_VALUE_NOT_USED_COERCION`  | lambdas passed to `dev.mokkery.every`, `everySuspend`, `verify`, `verifySuspend`; parameters annotated with Mokkery's internal `@Templating` |

## Raw compiler usage

Each rule diagnostic and each target is its own option, `<option>=<rule>=<value>`:

```shell
kotlinc -Xreturn-value-checker=full -Wextra \
  -Xplugin=shush-compiler-plugin-<version>.jar \
  -P plugin:io.github.tke.shush:preset=mokkery \
  -P plugin:io.github.tke.shush:baseDir=/path/to/project \
  -P plugin:io.github.tke.shush:rule=generated=REDUNDANT_VISIBILITY_MODIFIER \
  -P plugin:io.github.tke.shush:rule=generated=RETURN_VALUE_NOT_USED \
  -P plugin:io.github.tke.shush:files=generated=build/generated/** \
  -P plugin:io.github.tke.shush:callLambdas=templates=com.example.myTemplate \
  ...
```

| Option                                             | Repeatable | Meaning                                                        |
|----------------------------------------------------|------------|----------------------------------------------------------------|
| `preset=<name>`                                    | yes        | Enable a preset (`mokkery`)                                    |
| `baseDir=<path>`                                   | no         | Directory relative `files` globs are matched against           |
| `rule=<rule>=<DIAGNOSTIC>`                         | yes        | A diagnostic suppressed by `<rule>`: one option per diagnostic |
| `files=<rule>=<glob>`                              | yes        | See the DSL reference                                          |
| `calls=<rule>=<fq.function>`                       | yes        |                                                                |
| `annotatedCalls=<rule>=<fq.Annotation>`            | yes        |                                                                |
| `callLambdas=<rule>=<fq.function>`                 | yes        |                                                                |
| `annotatedParameterLambdas=<rule>=<fq.Annotation>` | yes        |                                                                |
| `annotatedDeclarations=<rule>=<fq.Annotation>`     | yes        |                                                                |

The Gradle plugin passes `baseDir` as an `InternalSubpluginOption`, which isn't a task input, so compile tasks stay
relocatable in the build cache.

## Usage (Kotlin toolchain)

Kotlin toolchain (Amper) projects configure compiler plugins in `module.yaml`, where `options` is a map and each
option can appear only once. Rule options therefore also take `;`-separated lists. An item without a `<rule>=`
prefix belongs to the previous item's rule, so `gen=A;B;tmpl=C` is `gen=A`, `gen=B`, `tmpl=C`.

```yaml
settings:
  kotlin:
    freeCompilerArgs: [ -Xreturn-value-checker=full, -Wextra ]
    compilerPlugins:
      - id: io.github.tke.shush
        dependency: io.github.tke:shush-compiler-plugin:<version>
        options:
          preset: mokkery
          rule: generated=REDUNDANT_VISIBILITY_MODIFIER;RETURN_VALUE_NOT_USED;templates=RETURN_VALUE_NOT_USED
          files: generated=**/src/generated/**
          annotatedDeclarations: templates=com.example.Template
```

- An item containing `=` always starts a new rule, so a glob containing `=` needs its rule prefix.
- Prefer `**/`-anchored globs. A relative `baseDir` such as `.` resolves against the compiler's working directory,
  which is only the module directory in single-module projects.
- The toolchain only resolves plugins from `https`/`http` Maven repositories, not `file:`.

Tested with Kotlin toolchain 0.13.0 (`kotlin build`).

## IDE support

In K2 mode IntelliJ IDEA only runs the compiler plugins bundled with it, so without help the editor still shows the
warnings the build suppresses. From 2026.3, IntelliJ also runs compiler plugins that declare the IDE builds they
support; _shush_ does, so supported IntelliJ builds load it from your build with no setup.

For more control, install
[Kotlin External FIR Support](https://plugins.jetbrains.com/plugin/26480-kotlin-external-fir-support) (KEFS, 2026.2
and later), which takes precedence when installed. It picks up _shush_ from your build with no configuration, isolates
its exceptions so a failing plugin is disabled instead of breaking the editor, and reloads it when the jar changes.
The jar carries the [Compiler Plugin DevKit](https://github.com/Kotlin/compiler-plugin-dev-kit)'s multi-release
markers, which KEFS uses to check the IDE's compiler is supported; an unsupported one shows up in the
*KEFS Diagnostics* tool window.

Alternatively, turn off the registry key `kotlin.k2.only.bundled.compiler.plugins.enabled`. That runs every compiler
plugin your projects apply, without KEFS's exception isolation.

## Compatibility

- Kotlin 2.4.20 to 2.5.x, K2 only, from a single jar. Older compilers are rejected with an error. Tested with
  2.4.20, 2.4.21 and 2.5.0-Beta2.
- Gradle 9.3+, tested with 9.3.1 and the wrapper's version. The Gradle plugin has no runtime dependencies.
- Mokkery 3.x, tested with 3.5.1.

The compiler APIs that differ between versions sit behind a small interface with one implementation per compiler
version, each compiled against that compiler (the approach [TestBalloon](https://github.com/infix-de/testBalloon)
uses). The newest one not newer than the running compiler is picked at runtime.

## Caveats

- Only warnings can be suppressed. Suppressing an error gets you `ERROR_SUPPRESSION` instead.
- Diagnostic names are the K2 ones, as printed by `-Xrender-internal-diagnostic-names`. Some need enabling first:
  `REDUNDANT_VISIBILITY_MODIFIER` needs `-Wextra`, `RETURN_VALUE_NOT_USED` needs `-Xreturn-value-checker`.
- Annotated local declarations (functions and classes inside a body) are matched by the annotation name as written,
  `Template` or `com.example.Template`, because their annotations aren't resolved yet on Kotlin 2.4. Import aliases
  aren't followed, and an unrelated annotation with the same simple name will match. Top-level and member
  declarations are matched exactly.
- The IDE runs an IntelliJ build of Kotlin (e.g. `2.4.20-ij262-52`) rather than a release, so its APIs could differ
  slightly from the layer's. If they do, KEFS disables _shush_ in the editor; the build isn't affected.
- With KEFS installed, KEFS loads _shush_ first. If KEFS disables it, a supported IntelliJ build loads it anyway,
  without KEFS's exception isolation.

## Building and testing

```shell
./gradlew check                 # compiler plugin tests + Gradle plugin functional tests
./gradlew :gradle-plugin:test   # Gradle TestKit functional tests only
./gradlew publishToMavenLocal   # version 0-SNAPSHOT
```

To try a local build, publish it to Maven Local and use version `0-SNAPSHOT` with `mavenLocal()` in both
`pluginManagement.repositories` (alongside `gradlePluginPortal()`, which declaring repositories there replaces) and
`dependencyResolutionManagement.repositories`.

- `-PkotlinCompilerVersion=<kotlin version>` builds and tests the compiler plugin against another compiler.
- `-PkotlinTestCompilerVersion=<kotlin version>` runs the compiler plugin tests on a different compiler from the one it's
  built against, for example to check the published jar on Kotlin 2.5.
- `-PfunctionalTestKotlinVersions=<a>;<b>` overrides the Kotlin Gradle plugin versions the functional tests use
  (default: the build's Kotlin version and 2.5.0-Beta2).

The functional tests publish both plugins to `build/functional-repo` and build real projects against them with
Gradle 9.3.1 and the wrapper's version.

Releases are published from version tags; see [RELEASING.md](RELEASING.md).

## License

[MIT](LICENSE)
