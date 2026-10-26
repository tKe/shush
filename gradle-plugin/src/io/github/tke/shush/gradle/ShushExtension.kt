package io.github.tke.shush.gradle

import org.gradle.api.Action
import org.gradle.api.DomainObjectSet
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Configuration of the `io.github.tke.shush` compiler plugin, available as `shush`.
 *
 * ```
 * shush {
 *     mokkery()                              // or mokkery.set(false) to opt out
 *     rule("generated-proto") {
 *         diagnostics.add("REDUNDANT_VISIBILITY_MODIFIER")
 *         files.add("build/generated/source/proto/{star}{star}") // {star} standing for *
 *     }
 *     rule {                                 // anonymous: named after a hash of its contents
 *         diagnostics.add("RETURN_VALUE_NOT_USED")
 *         annotatedCalls.add("com.example.Template")
 *     }
 * }
 * ```
 */
abstract class ShushExtension @Inject constructor(objects: ObjectFactory) {
    /**
     * Whether the `mokkery` preset is enabled: unused return value diagnostics are suppressed inside Mokkery's
     * `every`/`everySuspend`/`verify`/`verifySuspend` templates. Defaults to `true` when the `dev.mokkery` plugin is
     * applied, `false` otherwise.
     */
    abstract val mokkery: Property<Boolean>

    /** Custom suppression rules, see [ShushRuleSpec]. */
    val rules: NamedDomainObjectContainer<ShushRuleSpec> = objects.domainObjectContainer(ShushRuleSpec::class.java)

    /**
     * Rules declared without a name. Each gets one from a hash of its contents when its options are rendered, so
     * compile task inputs don't depend on declaration order.
     */
    val anonymousRules: DomainObjectSet<ShushRuleSpec> = objects.domainObjectSet(ShushRuleSpec::class.java)

    private val newAnonymousRule = { objects.newInstance(ShushRuleSpec::class.java, ShushRuleSpec.ANONYMOUS) }

    /** Enables the `mokkery` preset. */
    fun mokkery() {
        mokkery.set(true)
    }

    /** Creates (or configures, if it already exists) the rule called [name]. */
    fun rule(name: String, action: Action<in ShushRuleSpec>): ShushRuleSpec =
        rules.maybeCreate(name).also(action::execute)

    /** Creates an anonymous rule, which can't be referred to or extended elsewhere. */
    fun rule(action: Action<in ShushRuleSpec>): ShushRuleSpec =
        newAnonymousRule().also(action::execute).also(anonymousRules::add)
}
