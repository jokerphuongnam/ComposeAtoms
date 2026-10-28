# ComposeHooks

React-style hooks for Jetpack Compose with **owner lifetime** (state lives as long as the
`ViewModelStoreOwner` — nav back-stack entry / activity — not as long as the composition).

| Module | Package | What |
|---|---|---|
| `:hooks` | `compose.hooks` | core: `customRemember` scope tree, `rememberState`, `rememberEffect`, `rememberContext`, React hooks |
| `:query` | `compose.hooks.query` | Atoms port: `globalState`, `shareState`, `cache`, `ParentState` (depends on `:hooks` via `api`) |
| `:hooks-compiler` | – | K2 FIR checker (rules of hooks) |
| `:hooks-gradle-plugin` | – | plugin id `compose.hooks` wiring the compiler plugin |
| `:example` | – | sample app |

## Usage

```kotlin
import compose.hooks.customRemember
import compose.hooks.rememberState
import compose.hooks.rememberEffect
import compose.hooks.query.cache
import compose.hooks.query.rememberCache
import compose.hooks.query.rememberGlobalState
import compose.hooks.query.rememberShareState

private val profileQuery = cache<Profile>("profile", staleTime = 60.seconds) { api.profile() }

@Composable
fun rememberProfileForm() = customRemember {
    val session by rememberGlobalState { Session("Ada") }
    val profile = rememberCache(profileQuery)
    var name by rememberState("")
    rememberEffect(profile.data) { profile.data?.let { name = it.name } }
    ProfileForm(session, profile, name, onName = { name = it })
}

@Composable
fun ProfileScreen() {
    val form = rememberProfileForm()
    var note by rememberShareState("note") { Note("") }
    // ...
}
```

`:query` hooks work both inside and outside `customRemember` (bare hooks get an implicit scope).

## Core API (`compose.hooks`)

- `customRemember(key = null, name = "customRemember") { ... }` — groups hooks into a scope; nested
  `customRemember` = child scope. Scopes are stored in a `HooksViewModel` of the current
  `ViewModelStoreOwner`; cleared when the owner is cleared (nav pop / finish), children first.
  Without an owner (e.g. preview) falls back to `remember` + `RememberObserver`.
- `rememberState(policy) { init }` / `rememberState(value)` — `MutableState` surviving recreate/back.
  Note: in the value form the argument expression is still **evaluated every pass** (only the first value is stored).
- `rememberEffect(vararg deps) { ... }` — runs after commit (SideEffect) when deps change (`contentEquals`);
  no deps = once per owner lifetime. Receiver is a `CoroutineScope` cancelled before cleanup.
  If the block returns a `() -> Unit`, it is the cleanup.
  **Pitfall:** `launch { }` followed by `{ cleanup }` on the next line is parsed as a second trailing lambda — write
  `val cleanup = { ... }; cleanup`.
- `rememberContextOf<T>(name, default?)`, `ctx.Provider(value) { }`, `rememberContext(ctx)` — lending context:
  the lease is revoked when the provider's scope clears (`RevokedContextException`);
  no provider and no default → `MissingProviderException`.
- React hooks: `rememberReducer`, `rememberMemo(vararg deps)`, `rememberRef`, `rememberUpdated`,
  `rememberFlow(flow, initial, keys)`, `rememberId(prefix)`.
- Rules: hooks inside a scope must be called in the same order each pass (otherwise `IllegalStateException`).
  Use `key(id) { }` in loops. Errors from cleanups are reported via `Hooks.onError` (default rethrows).

**Stability note (C2):** a lambda that captures the `MutableState` object is memoized by strong skipping;
a lambda capturing a destructured setter (`val (v, set) = ...`) is a new lambda each pass.

## Migration from ComposeAtoms

Change the package `compose.atoms` → `compose.hooks.query`; function names are unchanged. Behaviour changes:

- `rememberAtom`, `rememberShareState`, `rememberCache` have **owner lifetime** (released on pop), not composition lifetime.
- `shareState` is ref-counted: after the last user leaves, the entry and its value are reset.
- `ParentState` is built on `rememberContext`; `LocalParentScope` / `ParentScope` removed;
  a missing parent throws `MissingProviderException`.
- `AtomState` gained `getValue` (delegation) and `component1/2`.
- `CacheQuery` equality is by entry.
- `rememberCache` subscribes **before** retaining (fixes a missed synchronous fetch result in Atoms).
- `cache(..., gcTime = ...) { }` with a trailing lambda is ambiguous between the sync/suspend overloads — use `fetch = suspend { }`.

## Compiler plugin

```kotlin
plugins { id("compose.hooks") }   // or kotlinCompilerPluginClasspath(project(":hooks-compiler"))
```

- `[E1]` hook called conditionally (error) / in a loop without `key` (warning)
- `[E2]` `rememberContextOf` without default and no `Provider` in the same file (error)
- `[B5]` lent context escaping into `GlobalScope.launch/async`, `thread {}`, `Thread {}` (warning)

Not done yet: P1 transforms (auto-key etc. — runtime fallback is used), cross-file E2 / cycle detection.

## Build

Requires JDK 17–21 for Gradle (Kotlin 2.0.21 does not run on JDK 25).
`./gradlew :hooks:testDebugUnitTest :query:testDebugUnitTest :hooks-compiler:test :example:assembleDebug`
