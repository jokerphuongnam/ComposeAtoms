# Changelog

All notable changes to ComposeHooks. Versions follow [Semantic Versioning](https://semver.org).

## 1.0.1

All seven artifacts are republished as 1.0.1. Only `hooks-test` changes in behaviour. There are no API changes.

### Fixed (`hooks-test`)

- A hook that returns `null` is no longer reported as "not committed". `HookResult.current` now fails only before the
  first commit, so a committed `null` is a valid value (`current == null`, `history` and `nextValue()` contain it).
- A missing parent no longer throws out of `renderHook` / `renderHookWithProps` / `HookResult.show()`. An exception
  thrown while composing (e.g. `MissingProviderException`) is collected with the other hook errors: expect it with
  `takeErrors()`, otherwise the test fails at the end as before. The failed composition is disposed (`isShown` is
  false) and nothing is committed.

## 1.0.0

First stable version: `hooks`, `query`, `hooks-compiler`, `hooks-gradle-plugin` (`compose.hooks`), `hooks-test` and
`query-test`.
