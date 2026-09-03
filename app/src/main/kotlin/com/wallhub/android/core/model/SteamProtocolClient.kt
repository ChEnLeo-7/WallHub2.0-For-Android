package com.wallhub.android.core.model

/**
 * Engine-neutral Steam protocol seam for the kSteam + Rust hybrid migration.
 *
 * The aggregate is intentionally the union of the five session-facing contracts so callers
 * and Hilt bindings never depend on a concrete engine. The active engine is JavaSteam
 * (`KSteamSessionRepository`); the kSteam engine implements the same aggregate
 * and swaps in behind this interface with no call-site changes.
 */
interface SteamProtocolClient :
    SteamSessionRepository,
    SteamContentCredentialProvider,
    AccountWorkshopRepository,
    SteamUnifiedWorkshopRepository,
    SteamPlaytimeRepository
