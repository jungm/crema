package io.github.jungm.crema.internal.security;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

import com.nimbusds.jose.util.DefaultResourceRetriever;

/**
 * Nimbus' {@link DefaultResourceRetriever} with redirects switched off, so that the Authorization Server's metadata
 * and keys are only ever read from the URLs Crema checked. A redirect response fails the retrieval, because Nimbus
 * accepts only {@code 2xx} responses.
 */
final class NoRedirectResourceRetriever extends DefaultResourceRetriever {

    /**
     * @param connectTimeout the connect timeout, in milliseconds
     * @param readTimeout the read timeout, in milliseconds
     * @param sizeLimit the maximum size of a retrieved document, in bytes
     */
    NoRedirectResourceRetriever(int connectTimeout, int readTimeout, int sizeLimit) {
        super(connectTimeout, readTimeout, sizeLimit);
    }

    @Override
    protected HttpURLConnection openHTTPConnection(URL url) throws IOException {
        HttpURLConnection connection = super.openHTTPConnection(url);
        connection.setInstanceFollowRedirects(false);
        return connection;
    }
}
