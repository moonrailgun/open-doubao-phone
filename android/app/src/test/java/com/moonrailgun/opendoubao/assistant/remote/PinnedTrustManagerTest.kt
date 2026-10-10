package com.moonrailgun.opendoubao.assistant.remote

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class PinnedTrustManagerTest {
    private val certificate = certificate(VALID_CERTIFICATE)
    private val renewed = certificate(RENEWED_CERTIFICATE)

    @Test fun acceptsTheExactPinnedLeafCertificate() {
        val trust = PinnedTrustManager(fingerprint(certificate))
        trust.verify(certificate)
        trust.checkServerTrusted(arrayOf(certificate), "EC")
        assertTrue(trust.acceptedIssuers.isEmpty())
    }

    @Test fun rejectsAMismatchingCertificate() {
        assertThrows(CertificateException::class.java) {
            PinnedTrustManager("0".repeat(64)).verify(certificate)
        }
    }

    @Test fun renewalWithTheSamePublicKeyStillNeedsANewCertificatePin() {
        assertArrayEquals(certificate.publicKey.encoded, renewed.publicKey.encoded)
        assertThrows(CertificateException::class.java) {
            PinnedTrustManager(fingerprint(certificate)).verify(renewed)
        }
    }

    @Test fun anExpiredCertificateIsRejectedEvenWhenItsPinMatches() {
        val expired = certificate(EXPIRED_CERTIFICATE)
        assertThrows(CertificateExpiredException::class.java) {
            PinnedTrustManager(fingerprint(expired)).verify(expired)
        }
    }

    @Test fun aMatchingIntermediateDoesNotAuthorizeADifferentLeaf() {
        assertThrows(CertificateException::class.java) {
            PinnedTrustManager(fingerprint(certificate)).checkServerTrusted(arrayOf(renewed, certificate), "EC")
        }
    }

    @Test fun anEmptyServerChainIsRejected() {
        assertThrows(CertificateException::class.java) {
            PinnedTrustManager(fingerprint(certificate)).checkServerTrusted(emptyArray(), "EC")
        }
    }

    private fun certificate(pem: String) = CertificateFactory.getInstance("X.509")
        .generateCertificate(pem.trimIndent().byteInputStream()) as X509Certificate

    private fun fingerprint(certificate: X509Certificate) = MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    companion object {
        // The two valid certificates share a key but have different expiry dates (2120 and 2121).
        internal const val VALID_CERTIFICATE = """
            -----BEGIN CERTIFICATE-----
            MIIBmDCCAT2gAwIBAgIUJ4+BKIu3VizpDeETH7+a2ZYsuocwCgYIKoZIzj0EAwIw
            IDEeMBwGA1UEAwwVT3Blbk9kb3ViYW8gdW5pdCB0ZXN0MCAXDTIwMDEwMTAwMDAw
            MFoYDzIxMjAwMTAxMDAwMDAwWjAgMR4wHAYDVQQDDBVPcGVuT2RvdWJhbyB1bml0
            IHRlc3QwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAT3aTFlW1oectCw+l5JLxFU
            UTmbm4qIKxaDvNdjhj7NLxLXNrALh9HgFlP7JSL2rUmNm3rpMhzAR7GdMEi1th7m
            o1MwUTAdBgNVHQ4EFgQU1R3kRchrkxSZDzWSp/zTN2gsctAwHwYDVR0jBBgwFoAU
            1R3kRchrkxSZDzWSp/zTN2gsctAwDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQD
            AgNJADBGAiEA+Qt2lyq4mPqRPmvPHU4J6+UEYtGl66frzUKCZtbsnFYCIQDIWjOZ
            1c6dOCGNTjYzqvlPuBXYoGvC7qbdKjR187F0AA==
            -----END CERTIFICATE-----
        """
        private const val RENEWED_CERTIFICATE = """
            -----BEGIN CERTIFICATE-----
            MIIBlzCCAT2gAwIBAgIUJ4+BKIu3VizpDeETH7+a2ZYsuocwCgYIKoZIzj0EAwIw
            IDEeMBwGA1UEAwwVT3Blbk9kb3ViYW8gdW5pdCB0ZXN0MCAXDTIwMDEwMTAwMDAw
            MFoYDzIxMjEwMTAxMDAwMDAwWjAgMR4wHAYDVQQDDBVPcGVuT2RvdWJhbyB1bml0
            IHRlc3QwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAT3aTFlW1oectCw+l5JLxFU
            UTmbm4qIKxaDvNdjhj7NLxLXNrALh9HgFlP7JSL2rUmNm3rpMhzAR7GdMEi1th7m
            o1MwUTAdBgNVHQ4EFgQU1R3kRchrkxSZDzWSp/zTN2gsctAwHwYDVR0jBBgwFoAU
            1R3kRchrkxSZDzWSp/zTN2gsctAwDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQD
            AgNIADBFAiAn0E1LdMLAptmiXHehfmQgG5ZetRBNAr3AKbuC0bQr1gIhAJDXrcRj
            uPf+vyFJiUGwBTJvppI+Mv4fH7bfyLryA8EL
            -----END CERTIFICATE-----
        """
        private const val EXPIRED_CERTIFICATE = """
            -----BEGIN CERTIFICATE-----
            MIIBlDCCATugAwIBAgIUJ4+BKIu3VizpDeETH7+a2ZYsuocwCgYIKoZIzj0EAwIw
            IDEeMBwGA1UEAwwVT3Blbk9kb3ViYW8gdW5pdCB0ZXN0MB4XDTE5MDEwMTAwMDAw
            MFoXDTIwMDEwMTAwMDAwMFowIDEeMBwGA1UEAwwVT3Blbk9kb3ViYW8gdW5pdCB0
            ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE92kxZVtaHnLQsPpeSS8RVFE5
            m5uKiCsWg7zXY4Y+zS8S1zawC4fR4BZT+yUi9q1JjZt66TIcwEexnTBItbYe5qNT
            MFEwHQYDVR0OBBYEFNUd5EXIa5MUmQ81kqf80zdoLHLQMB8GA1UdIwQYMBaAFNUd
            5EXIa5MUmQ81kqf80zdoLHLQMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwID
            RwAwRAIgAtCiyTMEd0bcgrX8BQKQdXbVyYoCovyEuJa4tmz8lKwCIGq6Ra+ROJFC
            P+3nUJml2usE3OC20szFA6LI8voigbBh
            -----END CERTIFICATE-----
        """
    }
}
