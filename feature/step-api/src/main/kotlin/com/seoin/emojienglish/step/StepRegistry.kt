package com.seoin.emojienglish.step

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt multibinding map of `type → StepFeature` (§4.4) — the merge device.
 *
 * The Player knows only this map; adding or removing a step module never touches
 * Player code. A missing type resolves to null → the Player shows the
 * "unsupported step" card (§0.6).
 *
 * Each step module contributes one binding:
 * ```
 * @Binds @IntoMap @StringKey("word_comic")
 * fun bind(impl: WordComicFeature): StepFeature
 * ```
 */
@Singleton
class StepRegistry @Inject constructor(
    private val features: Map<String, @JvmSuppressWildcards StepFeature>,
) {
    fun resolve(type: String): StepFeature? = features[type]

    /** Types bundled in this build — useful for master diagnostics. */
    fun supportedTypes(): Set<String> = features.keys
}
