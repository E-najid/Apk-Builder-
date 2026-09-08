package com.enajid.apkbuilder.data

import kotlinx.coroutines.CancellationException

class AuthRepository(
    private val deviceFlowClient: DeviceFlowClient,
    private val tokenStore: TokenStore,
    private val api: GitHubApi,
) {

    suspend fun requestDeviceCode(): DeviceCodeResult =
        deviceFlowClient.requestDeviceCode(GitHubAuth.CLIENT_ID, GitHubAuth.SCOPES)

    suspend fun pollForToken(deviceCode: String): TokenPollResult =
        deviceFlowClient.pollToken(GitHubAuth.CLIENT_ID, deviceCode)

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
