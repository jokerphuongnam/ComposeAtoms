// Stand-ins for the hooks-test fakes MissingProviderChecker accepts as providing a context.
@file:Suppress("UNUSED_PARAMETER")
package compose.hooks.test

import compose.hooks.RememberContext

class FakeProvision
infix fun <T> RememberContext<T>.provides(value: T): FakeProvision = FakeProvision()
fun <T> provide(context: RememberContext<T>, value: T): FakeProvision = FakeProvision()
