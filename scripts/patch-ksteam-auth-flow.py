#!/usr/bin/env python3
"""Patch the pinned kSteam source for reliable credential and CM sessions."""

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


web_api = Path("core/src/commonMain/kotlin/bruhcollective/itaysonlab/ksteam/web/WebApi.kt")
text = web_api.read_text(encoding="utf-8")
old = """            \"maxcount\" with 1

            if (configuration.cellId != 0) {
                \"cellid\" to configuration.cellId
            }
"""
new = """            \"maxcount\" with 20

            if (configuration.cellId != 0) {
                \"cellid\" with configuration.cellId
            }
"""
if old not in text:
    raise SystemExit("kSteam WebApi.kt CM discovery block did not match the pinned source")
web_api.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Patched kSteam CM discovery")


cm_list = Path("core/src/commonMain/kotlin/bruhcollective/itaysonlab/ksteam/network/CMList.kt")
text = cm_list.read_text(encoding="utf-8")
old = """    fun markEndpointAsBad() {
        logger.logWarning(\"CMList\") { \"Marking $wsEndpointSelected as bad.\" }

        if (wsEndpointIterator.hasNext()) {
            wsEndpointSelected = wsEndpointIterator.next()
            logger.logWarning(\"CMList\") { \"The next endpoint will be $wsEndpointSelected.\" }
        }
    }
"""
new = """    fun markEndpointAsBad(): Boolean {
        logger.logWarning(\"CMList\") { \"Marking $wsEndpointSelected as bad.\" }

        if (!wsEndpointIterator.hasNext()) return false
        wsEndpointSelected = wsEndpointIterator.next()
        logger.logWarning(\"CMList\") { \"The next endpoint will be $wsEndpointSelected.\" }
        return true
    }
"""
if old not in text:
    raise SystemExit("kSteam CMList.kt endpoint rotation block did not match the pinned source")
text = text.replace(old, new, 1)
old = """            wsEndpointSelected = wsEndpoints.first()
            wsEndpointIterator = wsEndpoints.iterator()
"""
new = """            wsEndpointSelected = wsEndpoints.first()
            wsEndpointIterator = wsEndpoints.drop(1).iterator()
"""
if old not in text:
    raise SystemExit("kSteam CMList.kt refresh block did not match the pinned source")
cm_list.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Patched kSteam CM endpoint rotation")


cm_client = Path("core/src/commonMain/kotlin/bruhcollective/itaysonlab/ksteam/network/CMClient.kt")
text = cm_client.read_text(encoding="utf-8")
old = """            if (currentCoroutineContext().isActive) {
                delay(1.seconds)
                connectivityStateDelayer.awaitUntilInternetConnection()
                connect()
            }
"""
new = """            if (currentCoroutineContext().isActive) {
                if (!serverList.markEndpointAsBad()) {
                    runCatching { serverList.refreshServerList() }
                        .onFailure { refreshError ->
                            logger.logWarning(\"CMClient:Connection\") {
                                \"Failed to refresh CM list: ${refreshError.message}\"
                            }
                        }
                }
                delay(1.seconds)
                connectivityStateDelayer.awaitUntilInternetConnection()
                connect()
            }
"""
if old not in text:
    raise SystemExit("kSteam CMClient.kt reconnect block did not match the pinned source")
cm_client.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Patched kSteam CM reconnect failover")
