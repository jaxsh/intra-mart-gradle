package io.github.jaxsh.intramart;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 插件自己上网（imm 仓库・Maven 的 BOM）用的 HTTP 客户端：走 Java 的系统代理设定，
 * 即 gradle.properties 里的 systemProp.http(s).proxyHost・proxyPort・nonProxyHosts（Gradle 自己下依赖也读这些）；
 * 有 systemProp.http(s).proxyUser・proxyPassword 时用它们做代理认证。
 * HttpClient 缺省不走代理（要显式给 ProxySelector），所以统一从这里建。
 */
final class Http {

    private Http() {
    }

    static HttpClient client() {
        final HttpClient.Builder b = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // 官方仓库只有 http，缺省的 HTTP/2 升级（h2c）会被服务器断开（EOF reached while reading），固定 HTTP/1.1
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault());
        if (System.getProperty("https.proxyUser") != null || System.getProperty("http.proxyUser") != null) {
            b.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() != RequestorType.PROXY) {
                        return null;
                    }
                    final String scheme = "https".equalsIgnoreCase(getRequestingProtocol()) ? "https" : "http";
                    final String user = System.getProperty(scheme + ".proxyUser", System.getProperty("http.proxyUser"));
                    final String password = System.getProperty(scheme + ".proxyPassword", System.getProperty("http.proxyPassword", ""));
                    return user == null ? null : new PasswordAuthentication(user, password.toCharArray());
                }
            });
        }
        return b.build();
    }
}
