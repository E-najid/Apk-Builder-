package com.enajid.apkbuilder.data

import com.enajid.apkbuilder.domain.ClientIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull

class AuthRepository(
    private val deviceFlowClient: DeviceFlowClient,
    private val tokenStore: TokenStore,
    private val api: GitHubApi,
) {

    /**
     * Starts the device flow with the best client ID available: the one saved
     * from the in-app setup screen if present, otherwise the one baked in at
     * build time. Returns [DeviceCodeResult.NeedsSetup] when neither is usable
     * so the UI can show the one-time setup screen.
     */
    suspend fun requestDeviceCode(): DeviceCodeResult {
        val clientId = resolveClientId()
        if (!ClientIds.isUsable(clientId)) return DeviceCodeResult.NeedsSetup
        return deviceFlowClient.requestDeviceCode(clientId, GitHubAuth.SCOPES)
    }

    suspend fun pollForToken(deviceCode: String): TokenPollResult {
        val clientId = resolveClientId()
        if (!ClientIds.isUsable(clientId)) {
            return TokenPollResult.Denied("The client ID was cleared. Start again.")
        }
        return deviceFlowClient.pollToken(clientId, deviceCode)
    }

    /** Persists a client ID entered in the setup screen (device-local only). */
    suspend fun saveClientId(clientId: String) {
        tokenStore.saveClientIdOverride(clientId)
    }

    private suspend fun resolveClientId(): String {
        val override = tokenStore.clientIdOverrideFlow.firstOrNull()?.trim()
        return override?.takeIf { it.isNotEmpty() } ?: GitHubAuth.CLIENT_ID
    }

    /**
     * Exchanges a fresh device-flow token for the signed-in user's login name
     * and persists it. Returns the login.
     */
    suspend fun completeSignIn(token: String): String {
        tokenStore.updateCached(token)
        return try {
            val user = api.currentUser()
            tokenStore.save(token, user.login)
            user.login
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tokenStore.updateCached(null)
            throw e
        }
    }

    suspend fun signOut() = tokenStore.clear()
}
