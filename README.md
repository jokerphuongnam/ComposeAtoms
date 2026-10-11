# ComposeAtoms

Jotai-style state for Jetpack Compose. There is no `ViewModel` and no `send`. A piece of state is an atom. A composable subscribes with a `remember` hook. Writing the atom recomposes every composable that reads it.

The atom does not keep a composable on screen. `parentState` is a `staticCompositionLocalOf` scope. It publishes the parent value down the tree and does not observe fields inside that value.

## Install

`minSdk` 26. Package name `compose.atoms`. Add the Maven repository that hosts the artifact, then:

```kotlin
dependencies {
    implementation("compose.atoms:atoms:1.0.0")
}
```

On this machine the artifact is in Maven local after `./gradlew :atoms:publishToMavenLocal`. The Android app then needs:

```kotlin
repositories {
    mavenLocal()
}
```

The app module already applies Jetpack Compose. Import `compose.atoms.rememberGlobalState` and the other hooks. The example app in this repo uses `implementation(project(":atoms"))` so it builds without publishing.

## Atoms

Create an atom outside composition so its identity is stable.

```kotlin
val count = globalState { 0 }

@Composable
fun Counter() {
    val state = rememberGlobalState { 0 }
    Button(onClick = { state.set(state.value + 1) }) {
        Text("${state.value}")
    }
}
```

`globalState` keeps one atom per type until the process exits. `Session` and `Title` are two atoms.

## Share

```kotlin
val note = shareState("note") { Note() }

@Composable
fun Editor() {
    val note = rememberShareState("note") { Note() }
    Text(note.value.text)
}
```

The same id and the same type are one atom. The same id and another type are another atom. `rememberShareState` holds the atom while that composable is in the tree. The last composable to leave releases it.

## Parent

```kotlin
@Composable
fun Wizard(wizard: WizardModel) {
    ParentState(wizard) {
        Step()
    }
}

@Composable
fun Step() {
    val wizard = rememberParentState<WizardModel>()
    Text(wizard.title)
}
```

`ParentState` uses `staticCompositionLocalOf`. Children see that value. Composables outside it do not. A sibling does not see the other sibling's model.

## Cache

The cache does not compare the fetch closure. It pairs the string key with the result type.

```kotlin
val fact = cache<String>(key = "fact", staleTime = 60.seconds) {
    fetchFact()
}

@Composable
fun FactScreen() {
    val fact = rememberCache(fact)
    Text(fact.data ?: if (fact.isFetching) "Loading" else "")
}
```

Same key and same result type share one in-flight fetch. Inside `staleTime` the next read returns the stored value. Same key and a different result type are a second cache. Default `staleTime` is zero. Default `gcTime` is five minutes after the last `rememberCache` leaves.

## Example

`:example` is a small Android app. `ExampleActivity` shows global, share, parent, and cache on one screen. A button changes the atom. The reader composable below it shows the same value.

```bash
./gradlew :example:assembleDebug
```
