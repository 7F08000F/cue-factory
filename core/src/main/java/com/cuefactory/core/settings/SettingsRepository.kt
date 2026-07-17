package com.cuefactory.core.settings

/**
 * Settings persistence port. App layer provides DataStore/SharedPreferences impl.
 */
interface SettingsRepository {
    fun get(): AppSettings
    fun update(transform: (AppSettings) -> AppSettings): AppSettings
}

/** In-memory implementation for tests and early wiring. */
class InMemorySettingsRepository(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {
    private var current: AppSettings = initial

    override fun get(): AppSettings = current

    override fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        current = transform(current)
        return current
    }
}
