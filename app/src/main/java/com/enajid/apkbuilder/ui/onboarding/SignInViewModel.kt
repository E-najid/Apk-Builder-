package com.enajid.apkbuilder.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.enajid.apkbuilder.ApkBuilderApp
import com.enajid.apkbuilder.data.DeviceCodeResult
import com.enajid.apkbuilder.data.TokenPollResult
import com.enajid.apkbuilder.data.friendlyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignInUiState(
    val loading: Boolean = true,
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
                    is DeviceCodeResult.Success -> {
                        _state.update {
                            it.copy(
                                loading = false,
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
