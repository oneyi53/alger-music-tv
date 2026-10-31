package com.wushisan.algertv.data;

import android.content.Context;
import android.util.Log;

import com.wushisan.algertv.App;
import com.wushisan.algertv.R;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * TLS trust support for old Android (the user's TV runs API 22).
 *
 * music-api.gdstudio.xyz sits behind Cloudflare with a Let's Encrypt
 * certificate: the RSA intermediates (R12/R13) chain to ISRG Root X1 and
 * the newer ECDSA intermediates (YR1/YR2) chain to ISRG Root X2 (verified
 * via crt.sh, 2026-10-23). Android's trust store only gained ISRG Root X1
 * in 7.1.1 and ISRG Root X2 in 14, and Let's Encrypt's old cross-sign
 * expired in 2024 — so on API 22 every GDStudio handshake died with
 * "Trust anchor for certification path not found" while phones and the
 * API 30 cloud emulator worked. Home metadata, playlist tracks, search
 * and playback URLs all failed on the TV.
 *
 * Fix: a composite trust manager — the platform trust store is tried
 * first; on failure the chain is retried against a keystore holding only
 * the two ISRG roots bundled in res/raw. This is NOT a trust-all manager:
 * chains anchored anywhere else still fail, and hostname verification is
 * untouched (OkHttp's default).
 */
public final class SslSupport {

    private static SSLSocketFactory socketFactory;
    private static X509TrustManager trustManager;

    private SslSupport() {}

    public static synchronized SSLSocketFactory socketFactory(Context ctx) {
        ensure(ctx);
        return socketFactory;
    }

    public static synchronized X509TrustManager trustManager(Context ctx) {
        ensure(ctx);
        return trustManager;
    }

    private static void ensure(Context ctx) {
        if (socketFactory != null && trustManager != null) return;
        try {
            X509TrustManager systemTm = systemTrustManager();
            X509TrustManager bundledTm = bundledTrustManager(ctx);
            if (systemTm == null && bundledTm == null) {
                throw new IllegalStateException("No usable X509TrustManager found");
            }
            trustManager = composite(systemTm, bundledTm);
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{trustManager}, null);
            socketFactory = sc.getSocketFactory();
            Log.i(App.TAG, "TLS_SUPPORT_READY bundled=" + (bundledTm != null));
        } catch (Exception e) {
            // Never end up worse than stock: fall back to platform defaults.
            Log.w(App.TAG, "TLS_SUPPORT_INIT_FAIL: " + e.getMessage());
            try {
                trustManager = systemTrustManager();
            } catch (Exception ignored) {
                trustManager = null;
            }
            if (trustManager != null) {
                try {
                    SSLContext sc = SSLContext.getInstance("TLS");
                    sc.init(null, new TrustManager[]{trustManager}, null);
                    socketFactory = sc.getSocketFactory();
                } catch (Exception ignored2) {
                    socketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                }
            } else {
                socketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            }
        }
    }

    private static X509TrustManager systemTrustManager() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        return pickX509(tmf.getTrustManagers());
    }

    private static X509TrustManager bundledTrustManager(Context ctx) {
        if (ctx == null) return null;
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int[] raws = {R.raw.isrg_root_x1, R.raw.isrg_root_x2};
            String[] aliases = {"isrgx1", "isrgx2"};
            for (int i = 0; i < raws.length; i++) {
                InputStream in = ctx.getResources().openRawResource(raws[i]);
                try {
                    Certificate cert = cf.generateCertificate(in);
                    ks.setCertificateEntry(aliases[i], cert);
                } finally {
                    in.close();
                }
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            return pickX509(tmf.getTrustManagers());
        } catch (Exception e) {
            Log.w(App.TAG, "TLS_BUNDLED_INIT_FAIL: " + e.getMessage());
            return null;
        }
    }

    private static X509TrustManager pickX509(TrustManager[] tms) {
        if (tms != null) {
            for (TrustManager tm : tms) {
                if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
            }
        }
        return null;
    }

    private static X509TrustManager composite(final X509TrustManager systemTm,
                                                 final X509TrustManager bundledTm) {
        if (systemTm == null) return bundledTm;
        if (bundledTm == null) return systemTm;

        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                try {
                    systemTm.checkClientTrusted(chain, authType);
                } catch (CertificateException e) {
                    bundledTm.checkClientTrusted(chain, authType);
                }
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                try {
                    systemTm.checkServerTrusted(chain, authType);
                } catch (CertificateException e) {
                    bundledTm.checkServerTrusted(chain, authType);
                    Log.i(App.TAG, "TLS_BUNDLED_ROOT_USED");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                List<X509Certificate> issuers = new ArrayList<>();
                X509Certificate[] systemIssuers = systemTm.getAcceptedIssuers();
                X509Certificate[] bundledIssuers = bundledTm.getAcceptedIssuers();
                if (systemIssuers != null) {
                    for (X509Certificate cert : systemIssuers) {
                        issuers.add(cert);
                    }
                }
                if (bundledIssuers != null) {
                    for (X509Certificate cert : bundledIssuers) {
                        if (!issuers.contains(cert)) {
                            issuers.add(cert);
                        }
                    }
                }
                return issuers.toArray(new X509Certificate[0]);
            }
        };
    }
}
