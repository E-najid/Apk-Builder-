package com.enajid.apkbuilder.ui.keystore

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.enajid.apkbuilder.ApkBuilderApp
import com.enajid.apkbuilder.data.ai.AiDebugLog
import com.enajid.apkbuilder.data.friendlyMessage
import com.enajid.apkbuilder.data.signing.KeystoreEntry
import com.enajid.apkbuilder.data.signing.KeystoreManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KeystoreViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val loading: Boolean = true,
        val entries: List<KeystoreEntry> = emptyList(),
        val activeName: String? = null,
        val busy: Boolean = false,
        val message: String? = null,
    )

    private val manager: KeystoreManager = (application as ApkBuilderApp).container.keystoreManager

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { s ->
                s.copy(
                    loading = false,
                    entries = manager.entries(),
                    activeName = manager.active()?.name,
                )
            }
        }
    }

    fun create(
        name: String,
        alias: String,
        storePassword: String,
        keyPassword: String,
        commonName: String,
        organization: String,
    ) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(busy = true) }
                val entry = manager.create(name, alias, storePassword, keyPassword, commonName, organization)
                _state.update {
                    it.copy(
                        busy = false,
                        entries = manager.entries(),
                        activeName = manager.active()?.name,
                        message = "Keystore তৈরি হয়েছে ✓ — এখন থেকে সব app-এর release APK এই দিয়ে sign হবে",
                    )
                }
                AiDebugLog.ok("keystore", "নতুন keystore তৈরি: ${entry.name} (alias=${entry.alias})")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AiDebugLog.error("keystore", "keystore তৈরি ব্যর্থ", t)
                _state.update { it.copy(busy = false, message = t.friendlyMessage()) }
            }
        }
    }

    fun import(name: String, uri: Uri, storePassword: String, keyPassword: String, aliasHint: String) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(busy = true) }
                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("ফাইল পড়া যায়নি")
                }
                val entry = manager.import(name, bytes, storePassword, keyPassword, aliasHint)
                _state.update {
                    it.copy(
                        busy = false,
                        entries = manager.entries(),
                        activeName = manager.active()?.name,
                        message = "Keystore import হয়েছে ✓ (alias: ${entry.alias})",
                    )
                }
                AiDebugLog.ok("keystore", "keystore import: ${entry.name} (alias=${entry.alias})")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AiDebugLog.error("keystore", "keystore import ব্যর্থ", t)
                _state.update { it.copy(busy = false, message = t.friendlyMessage()) }
            }
        }
    }

    fun setActive(name: String) {
        viewModelScope.launch {
            try {
                manager.setActive(name)
                _state.update { it.copy(activeName = name, message = "\"$name\" এখন সব app-এ ব্যবহার হবে") }
            } catch (t: Throwable) {
                _state.update { it.copy(message = t.friendlyMessage()) }
            }
        }
    }

    fun delete(name: String) {
        viewModelScope.launch {
            try {
                manager.delete(name)
                _state.update {
                    it.copy(
                        entries = manager.entries(),
                        activeName = manager.active()?.name,
                        message = "মুছে ফেলা হয়েছে",
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(message = t.friendlyMessage()) }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
