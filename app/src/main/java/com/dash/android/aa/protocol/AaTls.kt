package com.dash.android.aa.protocol

import java.io.File
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * The TLS layer of an Android Auto session.
 *
 * Android Auto carries a TLS 1.2 handshake **inside its own messages** (`SSL_HANDSHAKE` on the control
 * channel) and then encrypts every later frame's payload as TLS records. The head unit is the TLS
 * *client* and must present a client certificate the phone recognises as a head unit's. Java's
 * [SSLEngine] is a pure buffer-in/buffer-out TLS machine with no socket of its own, which is exactly the
 * shape needed: bytes go in from frames and come out as frames.
 *
 * **The certificate.** Like every open-source head unit, DASH-AA presents the publicly published
 * head-unit certificate that aasdk and openauto ship (`resources/aa/headunit-*.pem`, valid to 2045).
 * It is a file, not code: a user with a different certificate drops `headunit-cert.pem` and
 * `headunit-key.pem` (PKCS#8) into `<data folder>/aa/` and it is used instead.
 *
 * The phone's own certificate is not checked. The cable *is* the trust boundary — a phone plugged into
 * the car by its owner — which is the same position every open head unit takes.
 */
class AaTls(private val engine: SSLEngine) {

    private val netIn: ByteBuffer = ByteBuffer.allocate(BUFFER)       // ciphertext received, unconsumed
    private val appOut: ByteBuffer = ByteBuffer.allocate(BUFFER)      // scratch for decrypted output

    @Volatile var handshakeComplete: Boolean = false
        private set
    private var handshakeStarted = false

    init {
        netIn.flip()   // empty, in read mode
    }

    /**
     * Drive the handshake one step. [incoming] is the payload of an `SSL_HANDSHAKE` message from the
     * phone (null to start). Returns the bytes to send back in an `SSL_HANDSHAKE` message, or an empty
     * array when there is nothing to send yet.
     */
    @Synchronized
    fun handshake(incoming: ByteArray?): ByteArray {
        // An engine that has not begun reports NOT_HANDSHAKING, which must not be read as "done".
        if (!handshakeStarted) { engine.beginHandshake(); handshakeStarted = true }
        if (incoming != null) appendNetIn(incoming)
        val out = java.io.ByteArrayOutputStream()
        val netOut = ByteBuffer.allocate(BUFFER)
        loop@ while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val r = engine.wrap(EMPTY, netOut)
                    netOut.flip()
                    out.write(netOut.array(), 0, netOut.limit())
                    if (r.status == SSLEngineResult.Status.CLOSED) throw SSLException("TLS closed during handshake")
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP,
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN -> {
                    if (!netIn.hasRemaining()) break@loop
                    appOut.clear()
                    val r = engine.unwrap(netIn, appOut)
                    if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) break@loop
                    if (r.status == SSLEngineResult.Status.CLOSED) throw SSLException("TLS closed during handshake")
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    while (true) { (engine.delegatedTask ?: break).run() }
                }
                SSLEngineResult.HandshakeStatus.FINISHED,
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> {
                    handshakeComplete = true
                    break@loop
                }
                else -> break@loop
            }
        }
        return out.toByteArray()
    }

    /** Encrypt one frame's plaintext into TLS records. */
    @Synchronized
    fun encrypt(plain: ByteArray): ByteArray {
        val src = ByteBuffer.wrap(plain)
        val out = java.io.ByteArrayOutputStream(plain.size + 64)
        val netOut = ByteBuffer.allocate(BUFFER)
        while (src.hasRemaining()) {
            netOut.clear()
            val r = engine.wrap(src, netOut)
            if (r.status != SSLEngineResult.Status.OK) throw SSLException("encrypt failed: ${r.status}")
            netOut.flip()
            out.write(netOut.array(), 0, netOut.limit())
        }
        return out.toByteArray()
    }

    /** Decrypt one frame's TLS records. A record split across frames is held until it completes. */
    @Synchronized
    fun decrypt(cipher: ByteArray): ByteArray {
        appendNetIn(cipher)
        val out = java.io.ByteArrayOutputStream(cipher.size)
        while (netIn.hasRemaining()) {
            appOut.clear()
            val r = engine.unwrap(netIn, appOut)
            if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) break
            if (r.status != SSLEngineResult.Status.OK) throw SSLException("decrypt failed: ${r.status}")
            appOut.flip()
            out.write(appOut.array(), 0, appOut.limit())
            if (r.bytesConsumed() == 0 && r.bytesProduced() == 0) break
        }
        return out.toByteArray()
    }

    private fun appendNetIn(bytes: ByteArray) {
        netIn.compact()
        if (netIn.remaining() < bytes.size) throw SSLException("TLS input overflow")
        netIn.put(bytes)
        netIn.flip()
    }

    companion object {
        /** The head unit's side: TLS 1.2 client presenting the head-unit identity. */
        fun headUnit(dataDir: File?): AaTls {
            val (cert, key) = loadIdentity(dataDir)
            val keyManager = object : X509ExtendedKeyManager() {
                override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
                override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
                override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
                override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
                override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
                override fun getCertificateChain(alias: String?) = arrayOf(cert)
                override fun getPrivateKey(alias: String?) = key
            }
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLSv1.2")
            context.init(arrayOf(keyManager), arrayOf(trustAll), null)
            return AaTls(context.createSSLEngine().apply {
                useClientMode = true
                enabledProtocols = arrayOf("TLSv1.2")
            })
        }

        private const val ALIAS = "dash-aa-headunit"
        private const val BUFFER = 65536
        private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)

        /** The bundled identity, unless the user has supplied their own beside their data. */
        fun loadIdentity(dataDir: File?): Pair<X509Certificate, PrivateKey> {
            val userCert = dataDir?.let { File(it, "aa/headunit-cert.pem") }?.takeIf { it.isFile }
            val userKey = dataDir?.let { File(it, "aa/headunit-key.pem") }?.takeIf { it.isFile }
            val certPem = userCert?.readText() ?: resource("/aa/headunit-cert.pem")
            val keyPem = (if (userCert != null) userKey?.readText() else null) ?: resource("/aa/headunit-key.pem")

            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(certPem.byteInputStream()) as X509Certificate
            val der = Base64.getMimeDecoder().decode(
                keyPem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
            )
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
            // Touch the key store machinery once so a broken JRE fails here, loudly, not mid-handshake.
            KeyStore.getInstance(KeyStore.getDefaultType()).load(null, null)
            return cert to key
        }

        private fun resource(path: String): String =
            AaTls::class.java.getResourceAsStream(path)?.bufferedReader()?.readText()
                ?: error("Android Auto identity missing from the build: $path")
    }
}
