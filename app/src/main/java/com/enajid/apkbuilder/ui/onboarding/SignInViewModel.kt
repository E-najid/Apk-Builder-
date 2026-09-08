package com.enajid.apkbuilder.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.enajid.apkbuilder.ApkBuilderApp
import com.enajid.apkbuilder.data.DeviceCodeResult
import com.enajid.apkbuilder.data.TokenPollResult
import com.enajid.apkbuilder.data.friendlyMessage
import com.enajid.apkbuilder.domain.ClientIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignInUiState(
    val loading: Boolean = true,
    /** True when no usable OAuth client ID exists and the setup card should show. */
    val needsSetup: Boolean = false,
    val clientIdInput: String = "",
    val clientIdError: String? = null,
    val userCode: String? = null,
    val verificationUri: String? = null,
    val waiting: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

class SignInViewModel(application: Application) : AndroidViewModel(application) {

    private val authRepository = (application as ApkBuilderApp).container.authRepository

    private val _state = MutableStateFlow(SignInUiState())
    val state = _state.asStateFlow()

    init {
        start()
    }

    fun start() {
        viewModelScope.launch {
            try {
                _state.value = SignInUiState(loading = true)
                when (val result = authRepository.requestDeviceCode()) {
                    DeviceCodeResult.NeedsSetup -> _state.update {
                        it.copy(loading = false, needsSetup = true)
                    }
                    is DeviceCodeResult.Success -> {
                        _state.update {
                            it.copy(
                                loading = false,
                                needsSetup = false,
                                userCode = result.userCode,
                                verificationUri = result.verificationUri,
                                waiting = true,
                                error = null,
                            )
                        }
                        pollForApproval(
                            deviceCode = result.deviceCode,
                            intervalMs = result.intervalSeconds * 1000L,
                            expiresInMillis = result.expiresInSeconds * 1000L,
                        )
                    }
                    is DeviceCodeResult.Error -> _state.update {
                        it.copy(loading = false, error = result.message)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.friendlyMessage()) }
            }
        }
    }

    fun updateClientIdInput(value: String) {
        _state.update { it.copy(clientIdInput = value, clientIdError = null) }
    }

    /** Saves the client ID typed in the setup card and retries the device flow. */
    fun saveClientId() {
        val normalized = ClientIds.normalize(_state.value.clientIdInput)
        if (normalized == null) {
            _state.update {
                it.copy(
                    clientIdError = "That doesn't look like a client ID. Copy it from your " +
                        "OAuth app's settings page — it usually starts with \"Iv1.\"."
                )
            }
            return
        }
        viewModelScope.launch {
            try {
                authRepository.saveClientId(normalized)
                start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.friendlyMessage()) }
            }
        }
    }

    /** Lets the user jump from an error state back to the setup card. */
    fun showSetup() {
        _state.update { it.copy(loading = false, error = null, needsSetup = true) }
    }

    private suspend fun pollForApproval(deviceCode: String, intervalMs: Long, expiresInMillis: Long) {
        val deadline = System.currentTimeMillis() + expiresInMillis
        var interval = intervalMs
        while (System.currentTimeMillis() < deadline) {
            delay(interval)
            when (val result = authRepository.pollForToken(deviceCode)) {
                is TokenPollResult.Granted -> {
                    try {
                        authRepository.completeSignIn(result.accessToken)
                        _state.update { it.copy(waiting = false, done = true) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _state.update { it.copy(waiting = false, error = e.friendlyMessage()) }
                    }
                    return
                }
                TokenPollResult.Pending -> Unit
                TokenPollResult.SlowDown -> interval += 5_000L
                is TokenPollResult.Denied -> {
                    _state.update { it.copy(waiting = false, error = result.reason) }
                    return
                }
            }
        }
        _state.update {
            it.copy(waiting = false, error = "The sign-in code expired. Tap retry to get a new one.")
        }
    }
}
