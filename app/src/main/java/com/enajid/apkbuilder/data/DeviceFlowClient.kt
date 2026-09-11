package com.enajid.apkbuilder.data

import com.enajid.apkbuilder.domain.ClientIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed interface DeviceCodeResult {
    data class Success(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int,
    ) : DeviceCodeResult

    data class Error(val message: String) : DeviceCodeResult

    /** No usable OAuth client ID is configured — the app should show its setup screen. */
    data object NeedsSetup : DeviceCodeResult
}

sealed interface TokenPollResult {
    data class Granted(val accessToken: String) : TokenPollResult
    data object Pending : TokenPollResult
    data object SlowDown : TokenPollResult
    data class Denied(val reason: String) : TokenPollResult
}

/**
 * Implements the GitHub OAuth Device Flow: the user opens github.com/login/device
 * in a browser and types a short code, while we poll for the resulting token.
 * No client secret, no redirect URIs — perfect for a mobile app with no backend.
 */
class DeviceFlowClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    suspend fun requestDeviceCode(clientId: String, scopes: String): DeviceCodeResult =
        withContext(Dispatchers.IO) {
            try {
                val form = FormBody.Builder()
                    .add("client_id", clientId)
                    .add("scope", scopes)
                    .build()
                val request = Request.Builder()
                    .url("https://github.com/login/device/code")
                    .header("Accept", "application/json")
                    .post(form)
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext DeviceCodeResult.Error(errorFrom(response.code, body, clientId))
                    }
                    val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                        ?: return@withContext DeviceCodeResult.Error("Unexpected response from GitHub.")
                    val deviceCode = obj["device_code"]?.jsonPrimitive?.contentOrNull
                    val userCode = obj["user_code"]?.jsonPrimitive?.contentOrNull
                    if (deviceCode == null || userCode == null) {
                        return@withContext DeviceCodeResult.Error(errorFrom(response.code, body, clientId))
                    }
                    DeviceCodeResult.Success(
                        deviceCode = deviceCode,
                        userCode = userCode,
                        verificationUri = obj["verification_uri"]?.jsonPrimitive?.contentOrNull
                            ?: GitHubAuth.DEFAULT_VERIFICATION_URL,
                        intervalSeconds = obj["interval"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 5,
                        expiresInSeconds = obj["expires_in"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 900,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DeviceCodeResult.Error("Couldn't reach GitHub — check your internet connection.")
            }
        }

    suspend fun pollToken(clientId: String, deviceCode: String): TokenPollResult =
        withContext(Dispatchers.IO) {
            try {
                val form = FormBody.Builder()
                    .add("client_id", clientId)
                    .add("device_code", deviceCode)
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .build()
                val request = Request.Builder()
                    .url("https://github.com/login/oauth/access_token")
                    .header("Accept", "application/json")
                    .post(form)
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    obj?.get("access_token")?.jsonPrimitive?.contentOrNull?.let {
                        return@withContext TokenPollResult.Granted(it)
                    }
                    when (val error = obj?.get("error")?.jsonPrimitive?.contentOrNull) {
                        "authorization_pending" -> TokenPollResult.Pending
                        "slow_down" -> TokenPollResult.SlowDown
                        "expired_token" -> TokenPollResult.Denied("The code expired. Get a new one and try again.")
                        "access_denied" -> TokenPollResult.Denied("You denied the request. Tap retry to start again.")
                        "unsupported_grant_type", "incorrect_client_credentials", "incorrect_device_code" ->
                            TokenPollResult.Denied("GitHub rejected the sign-in ($error). Get a new code and try again.")
                        null -> TokenPollResult.Denied("Unexpected response from GitHub.")
                        else -> TokenPollResult.Denied("Sign-in failed ($error).")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TokenPollResult.Denied("Couldn't reach GitHub — check your internet connection.")
            }
        }

    /**
     * Turns a failed device-code response into something a human can act on.
     * The most common case by far is HTTP 404 "Not Found", which is what
     * GitHub returns for an unknown/placeholder client ID.
     */
    private fun errorFrom(code: Int, body: String, clientId: String): String {
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val described = obj?.get("error_description")?.jsonPrimitive?.contentOrNull
            ?: obj?.get("message")?.jsonPrimitive?.contentOrNull
            ?: obj?.get("error")?.jsonPrimitive?.contentOrNull
        val errorKey = obj?.get("error")?.jsonPrimitive?.contentOrNull.orEmpty()
        return when {
            errorKey.contains("device_flow", ignoreCase = true) ||
                described?.contains("device flow", ignoreCase = true) == true ->
                "Device Flow isn't enabled for this OAuth app. Open its settings on " +
                    "GitHub and tick \"Enable Device Flow\"."

            code == 404 || code == 401 ->
                "GitHub doesn't recognize the OAuth client ID this app is using (HTTP $code). " +
                    "Double-check the client ID — or use the button below to enter a different one."

            clientId.startsWith(ClientIds.PLACEHOLDER_PREFIX) ->
                "This build of APK Builder has no GitHub OAuth client ID set up yet. " +
                    "Setting one up is free and takes about two minutes — see below."

            described != null -> described
            else -> "GitHub returned HTTP $code."
        }
    }
}
