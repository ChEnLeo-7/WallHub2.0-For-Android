#!/usr/bin/env python3
"""Patch the pinned kSteam source for credential sessions without manual 2FA."""

from pathlib import Path


account = Path("core/src/commonMain/kotlin/bruhcollective/itaysonlab/ksteam/handlers/Account.kt")
text = account.read_text(encoding="utf-8")
old = """        val mappedConfirmations = signInResult.allowed_confirmations.mapNotNull { EAuthSessionGuardType.fromValue(it.confirmation_type ?: 0) }

        if (mappedConfirmations.let {
            it.contains(EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceConfirmation) || it.contains(EAuthSessionGuardType.k_EAuthSessionGuardType_EmailConfirmation)
        }) {
            createWatcherFlow(signInResult.interval ?: 5f)
        }
"""
new = """        val mappedConfirmations = signInResult.allowed_confirmations.mapNotNull { EAuthSessionGuardType.fromValue(it.confirmation_type ?: 0) }
        // A None (or empty) guard list completes through polling without user input. Without
        // this watcher, password-only credential sessions remain stuck in TFA.
        val hasNoGuard = mappedConfirmations.isEmpty() || mappedConfirmations.any {
            it == EAuthSessionGuardType.k_EAuthSessionGuardType_None
        }
        val hasAutomaticConfirmation = mappedConfirmations.any {
            it == EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceConfirmation ||
                it == EAuthSessionGuardType.k_EAuthSessionGuardType_EmailConfirmation
        }

        if (hasAutomaticConfirmation || hasNoGuard) {
            createWatcherFlow(signInResult.interval ?: 5f)
        }
"""
if old not in text:
    raise SystemExit("kSteam Account.kt auth block did not match the pinned source")
account.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Patched kSteam credential auth polling")
