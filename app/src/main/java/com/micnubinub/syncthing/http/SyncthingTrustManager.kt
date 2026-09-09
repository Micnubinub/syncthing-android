package com.micnubinub.syncthing.http

import android.annotation.SuppressLint
import android.util.Log
import com.micnubinub.syncthing.util.Util.osTrustManager
import java.io.File
import java.io.FileInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/*
 * TrustManager checking against the local Syncthing instance's https public key.
 *
 * Based on http://stackoverflow.com/questions/16719959#16759793
 *
 * The local Syncthing instance ships a self-signed certificate by default, which is verified by
 * pinning against the public key stored in https-cert.pem. A user may instead replace the HTTPS
 * certificate with one signed by a CA they trust at the Android OS level (see
 * https://github.com/researchxxl/syncthing-android/issues/222); for that case we fall back to the
 * OS trust store when the self-signed pin does not match.
 *
 * Security scope: this trust manager is only ever wired into the loopback-pinned connection to the
 * local Syncthing instance (see ApiRequest#forceLoopbackHost). Because that connection cannot leave
 * the device, falling back to the OS trust store — which trusts user-installed CAs — does not open a
 * network MITM surface. Do not reuse this trust manager for any routable/remote connection.
 */
internal class SyncthingTrustManager(private val mHttpsCertPath: File) : X509TrustManager {
    /**
     * Parsed certificate from [.mHttpsCertPath], cached so a TLS handshake does not
     * re-read and re-parse the file from disk on every request. [SyncthingHttpClients.invalidate]
     * rebuilds the [OkHttpClient] with a fresh [SyncthingTrustManager] whenever the certificate
     * file changes, so the cache is always consistent with the file.
     */
    @Volatile
    private var pinnedCertificate: X509Certificate? = null

    @SuppressLint("TrustAllX509TrustManager")
    @Throws(CertificateException::class)
    override fun checkClientTrusted(chain: Array<X509Certificate?>?, authType: String?) {
        // No-op: this trust manager is used exclusively by the app as a TLS *client* on
        // loopback-only connections (see ApiRequest#forceLoopbackHost). The Syncthing daemon
        // never requests client certificates, so there is nothing to verify here. The loopback
        // invariant is enforced at the call site; if this trust manager is ever wired into a
        // non-loopback connection, the hostnameVerifier gate in SyncthingHttpClients will reject
        // the host before any TLS handshake occurs.
    }

    /**
     * Verifies certs against the public key of the local syncthing instance (self-signed pin).
     * If that fails, falls back to the Android OS trust store, which validates CA-signed
     * certificates against the system and user-installed certificate authorities.
     */
    @Throws(CertificateException::class)
    override fun checkServerTrusted(
        certs: Array<X509Certificate>,
        authType: String?
    ) {
        try {
            verifyAgainstPinnedCert(certs)
        } catch (pinFailure: CertificateException) {
            // The presented certificate is not the pinned self-signed certificate. This is expected
            // when the user replaced the HTTPS certificate with a CA-signed one, so fall back to the
            // Android OS trust store instead of failing the connection outright. Logged at debug
            // level to avoid spamming logcat with the wrapped BAD_SIGNATURE on every request.
            Log.d(TAG, "Pinned certificate did not match, trying Android OS trust store.")
            val osTrustManager = osTrustManager ?: throw pinFailure
            osTrustManager.checkServerTrusted(certs, authType)
        }
    }

    /**
     * Verifies that every presented certificate is signed by the public key of the certificate
     * pinned in [.mHttpsCertPath] (the certificate the local syncthing instance generated).
     */
    @Throws(CertificateException::class)
    private fun verifyAgainstPinnedCert(certs: Array<X509Certificate>) {
        try {
            val ca = pinnedCertificate ?: FileInputStream(mHttpsCertPath).use { inputStream ->
                val cf = CertificateFactory.getInstance("X.509")
                (cf.generateCertificate(inputStream) as X509Certificate).also {
                    pinnedCertificate = it
                }
            }
            for (cert in certs) {
                cert.verify(ca.publicKey)
            }
        } catch (e: Exception) {
            throw CertificateException("Untrusted Certificate!", e)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate?>? {
        return emptyArray()
    }

    companion object {
        private const val TAG = "SyncthingTrustManager"
    }
}
