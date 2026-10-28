# ComposeHooks 1.0.0

React-style hooks for Jetpack Compose with **owner lifetime**: hook state lives as long as the
`ViewModelStoreOwner` (navigation back-stack entry / activity), not as long as one composition. A query layer
(React Query / Jotai style), a K2 compiler plugin that checks the rules of hooks, and test kits.

| Artifact (`compose.hooks:<name>:1.0.0`) | Package | What |
|---|---|---|
| `hooks` (AAR) | `compose.hooks` | core: `customRemember` scope tree, `rememberState`, `rememberEffect`, `rememberContext`, React hooks |
| `query` (AAR) | `compose.hooks.query` | `QueryClient`, `cache` / `rememberCache`, `seedCache`, `globalState`, `shareState`, `ParentState` (`api` on `hooks`) |
| `hooks-compiler` (JAR) | `compose.hooks.compiler` | K2 FIR checks E1 / E2 / E3 / B5 |
| `hooks-gradle-plugin` (JAR) + marker `compose.hooks.gradle.plugin` | `compose.hooks.gradle` | Gradle plugin id `compose.hooks` |
| `hooks-test` (AAR) | `compose.hooks.test` | `runHookTest` / `renderHook`: JVM unit tests for hooks, virtual time |
| `query-test` (AAR) | `compose.hooks.query.test` | `runQueryTest`: `hooks-test` + an isolated `QueryClient` per test |

`:example` is a sample app in this repository only. It is never published and is not part of any artifact.

Built with Kotlin 2.3.21 (Compose compiler plugin 2.3.21), AGP 8.7.3, Gradle 8.11.1, Compose runtime 1.7.6, lifecycle 2.8.7, coroutines 1.9.0. minSdk 26.

## Installation

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven("https://maven.pkg.github.com/jokerphuongnam/ComposeHooks") {
            credentials {          // GitHub Packages needs a token with read:packages, even for public packages
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
        // or mavenLocal() after `./gradlew publishToMavenLocal` in this repository
    }
}

// module build.gradle.kts
dependencies {
    implementation("compose.hooks:hooks:1.0.0")
    implementation("compose.hooks:query:1.0.0")
    testImplementation("compose.hooks:hooks-test:1.0.0")
    testImplementation("compose.hooks:query-test:1.0.0")   // includes hooks-test
}
```

Put `gpr.user` / `gpr.key` in `~/.gradle/gradle.properties`, never in a repository.

## Hooks (`compose.hooks`)

```kotlin
@Composable
fun rememberCounter(step: Int) = customRemember {
    var count by rememberState(0)
    val square = rememberMemo(count) { count * count }
    rememberEffect(count) { println("count=$count") }
    Counter(count, square, onInc = { count += step })
}
```

- `customRemember(key = null, name = "customRemember") { ... }` groups hooks into a scope; a nested `customRemember`
  is a child scope. Scopes are stored in a `ViewModel` of the current `ViewModelStoreOwner` and cleared when the owner
  is cleared (navigation pop, activity finish), children first. They survive recomposition, rotation and the screen
  being hidden on the back stack. With no owner (Preview, inspection) they fall back to `remember`, so they live
  as long as the composition. Bare hooks outside `customRemember` get an implicit scope.
- `rememberState { init }` / `rememberState(value)` / `rememberState(policy) { init }` is a `MutableState` with owner
  lifetime. In the value form the argument is still **evaluated on every pass**; only the first value is stored.
- `rememberEffect(vararg deps) { ... }` runs after commit when deps change (`contentEquals`); no deps means once per
  owner lifetime. The receiver (`EffectScope`) is a `CoroutineScope` cancelled before the cleanup runs. If the block
  returns a `() -> Unit`, that is the cleanup. **Pitfall:** `launch { }` followed by `{ cleanup }` on the next line is
  parsed as a second trailing lambda. Write `val cleanup = { ... }; cleanup` instead.
- Context: `val Theme = rememberContextOf<String>("Theme", default = null)`, `Theme.Provider(value) { ... }`,
  `rememberContext(Theme)`. Values are *lent*: the lease is revoked when the provider's owner is cleared, and a
  late reader gets `RevokedContextException`. With no provider and no default, reading throws
  `MissingProviderException` (checked at compile time by E2).
- React hooks: `rememberReducer(reducer) { init }`, `rememberMemo(vararg deps) { }`, `rememberRef { }`,
  `rememberUpdated(value)`, `rememberFlow(flow, initial, keys)`, `rememberId(prefix)`.
- Rules: inside a scope, hooks must be called in the same order on every pass (else `IllegalStateException`). Use
  `key(id) { }` in loops. Errors from effects, cleanups and effect coroutines go to `Hooks.onError` (by default it
  rethrows).
- Stability: a lambda that captures the `MutableState` object is memoized by strong skipping. A lambda that captures
  a destructured setter (`val (v, set) = ...`) is a new lambda on every pass.

## Query (`compose.hooks.query`)

```kotlin
private val profileQuery = cache<Profile>("profile", staleTime = 60.seconds) { api.profile() }

@Composable
fun rememberProfileForm() = customRemember {
    val session = rememberGlobalState { Session("Ada") }    // one value per type, per client
    val profile = rememberCache(profileQuery)               // CacheResult: data / error / isFetching / status / refetch()
    val note = rememberShareState("note") { Note("") }      // shared by id while someone uses it (ref-counted)
    ProfileForm(session.value, profile.data, onLogin = { session.set(Session(it)) })
}
```

- `cache(key, staleTime = 0, gcTime = 5.minutes, client = null) { suspend fetch }` is a `CacheQuery` **handle**: a
  key + type, not an entry. Creating it has no side effect. `rememberCache(query)` leases the entry for the caller's
  owner: it fetches when the entry is missing or stale, refetches when the screen comes back after `staleTime`, and
  starts the `gcTime` countdown when the owner is cleared. Same key + same type means one entry and one in-flight
  fetch per client; another type is another entry.
- Several observers of one entry follow React Query rules. The entry is stale after the **smallest** `staleTime` among
  live leases. It is kept for the **largest** `gcTime` ever leased. The most recently leased fetch runs.
- `cachedValue(key, staleTime = 0, gcTime = 5.minutes, client = null) { fetch }` is the synchronous form. It runs
  `fetch` on the caller and returns the value, unless a value fresh for this call's `staleTime` (or a fresh seed) is
  cached. It uses the same entry as `cache(key)` queries of the same type.
- `seedCache(key, value, freshFor = INFINITE)` / `client.seedCache(...)` puts a value in as if it had just been
  fetched (an offline snapshot, a deep-link payload, a test fixture). While it is fresh, observing it does not fetch.
  A real fetch replaces it.
- `cacheState<T>(key)` returns the current state without subscribing.
- `globalState { }` / `rememberGlobalState { }` is one atom per type. `shareState(id) { }` /
  `rememberShareState(id) { }` shares an atom by id while it has users. `ParentState(value) { }` /
  `rememberParentState<T>()` passes state down a subtree (built on `rememberContext`).

### QueryClient and QueryConfig

A `QueryClient` is a store (cache entries + atoms) plus a `QueryConfig` (where fetches and gc countdowns run, and the
clock for staleTime). It works like React Query's `QueryClient`.

```kotlin
QueryConfig.global = QueryConfig(context = Dispatchers.IO)        // the Default client's config

@Composable
fun FeedScreen() {
    val client = rememberQueryClient(LocalQueryClient.current.config) // cleared when this screen is popped
    rememberMemo(client) { client.seedCache("feed-0", OFFLINE_FIRST_PAGE, freshFor = 5.seconds) }
    QueryClientProvider(client) { Feed() }                         // rememberCache / rememberGlobalState / rememberShareState use it
}
```

- `QueryClient.Default` is the process-wide client. Every call that names no client uses it, and it is the default
  of `LocalQueryClient`.
- `QueryClientProvider(client) { }` makes `client` current for a subtree. The handle of a query built without
  `client = ...` resolves `LocalQueryClient` inside `rememberCache`, and resolves `Default` elsewhere.
- `rememberQueryClient(config)` returns a client owned by the caller's owner. It survives back navigation and
  rotation, and is cleared on pop.
- `client.clear()` drops every entry: in-flight fetches and countdowns are cancelled, entries are reset to Pending,
  and observers on screen fetch again. Handles stay valid. `client.resetGlobalStates()` forgets global states.
  `CacheRegistry.clear()` and `resetGlobalStates()` (top level) act on `Default`.
- `QueryConfig(context = Dispatchers.Default, nowMillis = { System.currentTimeMillis() })`: any dispatcher works,
  including a `TestDispatcher`. A Job in `context` becomes the parent of the cache work. A config change applies to
  work started afterwards.
- Every member is thread-safe. Each client has one lock, and listeners and fetch bodies run outside it.

## Compiler checks

The Gradle plugin adds the compiler plugin (K2 only). Every check is a compile error or warning in the editor and in
the build:

| Id | Severity | Rule |
|---|---|---|
| `E1` | error | A hook is called conditionally: inside `if` / `when` branches, a later `when` condition, `try` / `catch`, the right side of `&&` / `\|\|`, the right side of `?:`, or after `?.` |
| `E1` | warning | A hook is called in a loop without `key(...) { }` |
| `E2` | error | `rememberContext(ctx)` for a `rememberContextOf` with no default, and no `ctx.Provider(...)` in the same file. `hooks-test`'s `ctx provides v` / `provide(ctx, v)` count as providers |
| `E3` | error | Unstable cache key: the `key` of `cache(...)` / `cachedValue(...)` or the `id` of `shareState` / `rememberShareState` calls `System.nanoTime/currentTimeMillis`, `Math.random`, `UUID.randomUUID`, `Uuid.random`, `kotlin.random.Random*`, `random()` / `shuffled()` on collections / ranges / strings, `ThreadLocalRandom.current`, `SystemClock.*`, `TimeSource.Monotonic.markNow` or `now()` (java.time / kotlinx.datetime), inside a hook body (`@Composable` function or `customRemember`). Each pass would name a new entry and fetch again. Compute the key once with `remember { }`, or take it from a parameter or state. |
| `B5` | warning | A lent context value escapes into `GlobalScope.launch/async`, `thread { }` or `Thread { }` |

Event callbacks, effect bodies, fetch bodies and top-level properties are not hook bodies, so E1 and E3 ignore them.
Not done yet: P1 transforms (auto-key; the runtime fallback is used), cross-file E2, cycle detection.

## Gradle plugin

```kotlin
// settings.gradle.kts: pluginManagement { repositories { gradlePluginPortal(); google(); mavenCentral(); maven(<GitHub Packages, as above>) } }
plugins {
    id("com.android.library")                   // or application
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("compose.hooks") version "1.0.0"
}
```

- Adds `compose.hooks:hooks-compiler:1.0.0` to every Kotlin compilation, unit tests included.
- In Android modules it sets `android.testOptions.unitTests.isReturnDefaultValues = true`, because the Compose runtime
  calls `android.os.Trace`, which throws in JVM unit tests. It is set when the plugin is applied, so an explicit value
  in your `android { }` block still wins.
- It warns when a Kotlin module does not apply `org.jetbrains.kotlin.plugin.compose`.
- **Kotlin version:** the compiler checks use the K2 FIR API, which changes between Kotlin releases. They support
  **Kotlin 2.2.20 up to, but not including, 2.4.0**; the fixture tests pass on 2.2.20, 2.2.21, 2.3.0, 2.3.10 and
  2.3.21. On any other version the plugin adds no compiler checks and logs `compose.hooks: Kotlin X is not supported
  by the ComposeHooks compiler checks ...`; the build continues and `isReturnDefaultValues` is still set. The
  `hooks` / `query` / test libraries are ordinary Compose libraries and do not depend on the compiler version.

## Testing hooks (`hooks-test`, `query-test`)

JVM unit tests (`src/test`). There is no device and no Robolectric, and every test runs on virtual time.

```kotlin
class CounterTest {
    @Test fun incrementsAndKeepsStateAcrossRecreate() = runHookTest {
        val entry = backStackEntry()                         // a navigation back-stack entry (owner)
        val counter = renderHook(entry) { rememberCounter(step = 2) }
        counter.act { counter.current.onInc() }
        assertEquals(2, counter.current.count)
        counter.recreate()                                   // rotation: same owner, new composition
        assertEquals(2, counter.current.count)
        entry.pop()                                          // owner cleared: cleanups run
        assertTrue(counter.wasCleared)
        assertEquals(0, entry.hookScopeCount)                // nothing leaked
    }
}
```

`runHookTest(timeout = 10.seconds) { }` gives a `HookTestScope`:

- `renderHook(owner = backStackEntry(), provide = listOf(Ctx provides v), wrapper = null, strictHistory = false) { hook() }`
  and `renderHookWithProps(props) { p -> hook(p) }` return a `HookResult`. Its members are `current`, `history`,
  `recomposeCount`, `act { }`, `rerender(props)`, `hide()` / `show()` / `recreate()`, `isShown`, `wasCleared`, and
  `owner`. Pass `owner = null` for the no-owner (Preview) path.
- `backStackEntry()` returns an entry with `pop()` (hides, then clears; idempotent), `isPopped` and `hookScopeCount`.
- Strict history: `nextValue()`, `expectNoValue()`, `skipValues(n)`, `ignoreRemaining()`, `unconsumed`. With
  `strictHistory = true`, the test fails when values are left unconsumed.
- Time: `advanceTimeBy(ms | Duration)`, `advanceUntilIdle()`, `runCurrent()`, `currentTime`, `testScope`,
  `mainDispatcher` (`Dispatchers.Main` during the test).
- Errors: failures of `Hooks.onError`, composition and hook coroutines are collected. The test fails if any are left,
  so call `takeErrors()` to expect them.
- Wrappers and teardown: `addWrapper { content -> }` and `onTeardown { }`. At the end, every composition is
  disposed, every live entry is popped (endless effect loops stop), teardown callbacks run in reverse order, and
  `Hooks.onError` and `Dispatchers.Main` are restored.

`runQueryTest { }` adds query isolation. A fresh `client` on virtual time is provided to every rendered hook, and
`QueryClient.Default` also runs on virtual time. Both are cleared at teardown, and the Default config is restored.
Helpers: `seedCache(key, value, freshFor)`, `fakeCache(key, value)` (forever fresh, so the real fetch never runs on
its own) and `cacheState<T>(key)`.

```kotlin
@Test fun fakedProfile() = runQueryTest {
    fakeCache("profile", Profile("Test user"))
    val form = renderHook { rememberProfileForm() }
    assertEquals("Test user", form.current.name)
}
```

See `example/src/test` for tests of a real screen, including a screen-scoped client rendered with `wrapper`.

## Migration from ComposeAtoms

This repository was previously named ComposeAtoms and published `compose.atoms:atoms`. Change the package
`compose.atoms` to `compose.hooks.query`; function names are unchanged except the one below. Behaviour changes:

- The synchronous `cache(key, staleTime, gcTime) { value }` (which returned `T`) is now `cachedValue(...)`. `cache(...)`
  always returns a `CacheQuery`, so `cache(key, gcTime = X) { fetch }` with a trailing lambda is a query. A call that
  passed a non-suspend `() -> T` and used the result as `T` no longer type-checks there; rename it to `cachedValue`.

- `rememberAtom`, `rememberShareState` and `rememberCache` have **owner lifetime** (released on pop), not composition
  lifetime.
- `shareState` is ref-counted: after the last user leaves, the entry and its value are reset.
- `ParentState` is built on `rememberContext`. `LocalParentScope` / `ParentScope` were removed, and a missing parent
  throws `MissingProviderException`.
- `AtomState` gained `getValue` (delegation) and `component1/2`. `CacheQuery` equality is by key + type + client.
- `rememberCache` subscribes **before** retaining, which fixes a missed synchronous fetch result in Atoms.
- Stores are per `QueryClient`; the old global behaviour is `QueryClient.Default`.

## Build, test, publish (this repository)

JDK 17–21 for Gradle (Gradle 8.11.1 does not run on JDK 25).

```sh
./gradlew test koverVerify :hooks:koverVerifyDebug :query:koverVerifyDebug :hooks-test:koverVerifyDebug :query-test:koverVerifyDebug
./gradlew :example:assembleDebug
./gradlew dokkaHtml                 # KDoc check: reports "Undocumented" public API
./gradlew :hooks-compiler:test -PfixtureKotlin=2.2.20   # compiler checks on another supported Kotlin version
ANDROID_SERIAL=<device> ./gradlew :hooks:connectedDebugAndroidTest :example:connectedDebugAndroidTest
./gradlew publishToMavenLocal       # all six artifacts + plugin marker to ~/.m2 (never :example)
GITHUB_ACTOR=<user> GITHUB_TOKEN=<token with write:packages> ./gradlew publish   # GitHub Packages
```

Coverage gate (Kover, JVM unit tests): line ≥ 95% and branch ≥ 80% in every published module.

## License

Apache License 2.0, see [LICENSE](LICENSE). The published POMs carry the same license (`gradle.properties`, `pom.*`).
