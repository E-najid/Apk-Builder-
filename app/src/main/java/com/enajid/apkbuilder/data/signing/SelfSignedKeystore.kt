package com.enajid.apkbuilder.data.signing

import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Creates self-signed PKCS#12 keystores for release signing. Pure JVM (works
 * in unit tests); BouncyCastle provides the certificate builder Android's
 * stripped internal copy doesn't expose.
 */
object SelfSignedKeystore {

    private const val PROVIDER = "BC"

    init {
        // Android ships a gutted "BC"; replace it with the real one once.
        runCatching {
            Security.removeProvider(PROVIDER)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    /**
     * @return PKCS#12 bytes holding one RSA 2048 key + self-signed cert
     *         (valid ~30 years), encrypted with [storePassword].
     */
    fun generate(
        alias: String,
        storePassword: String,
        keyPassword: String,
        commonName: String,
        organization: String,
    ): ByteArray {
        require(alias.isNotBlank()) { "alias খালি হতে পারে না" }
        require(storePassword.length >= 6) { "store password অন্তত ৬ অক্ষরের হতে হবে" }
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()

        val now = Date()
        val till = Date(now.time + 30L * 365L * 24L * 60L * 60L * 1000L)
        val principal = X500Principal(
            "CN=${commonName.ifBlank { "APK Builder" }}, O=${organization.ifBlank { "APK Builder" }}"
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val holder = JcaX509v3CertificateBuilder(
            principal, BigInteger(160, SecureRandom()), now, till, principal, keyPair.public
        ).build(signer)
        val certificate: X509Certificate = JcaX509CertificateConverter().getCertificate(holder)

        val store = KeyStore.getInstance("PKCS12", PROVIDER)
        store.load(null)
        store.setKeyEntry(alias, keyPair.private, keyPassword.toCharArray(), arrayOf(certificate))
        return ByteArrayOutputStream().also { store.store(it, storePassword.toCharArray()) }.toByteArray()
    }

    /**
     * Checks that [bytes] is a loadable keystore and returns the key alias —
     * auto-detected when [aliasHint] is blank.
     */
    fun validate(bytes: ByteArray, storePassword: String, aliasHint: String): String {
        val store = KeyStore.getInstance("PKCS12")
        store.load(ByteArrayInputStream(bytes), storePassword.toCharArray())
        val alias = aliasHint.takeIf { it.isNotBlank() }
            ?: store.aliases().toList().firstOrNull { store.isKeyEntry(it) }
            ?: error("এই keystore-এ কোনো key entry নেই")
        require(store.isKeyEntry(alias)) { "alias '$alias'-এ key নেই" }
        return alias
    }
}
