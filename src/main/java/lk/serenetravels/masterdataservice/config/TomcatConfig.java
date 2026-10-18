package lk.serenetravels.masterdataservice.config;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TomcatConfig {

    // Default NIO connector's Selector wakeup pipe fails in this environment
    // (JDK-8312215: AF_UNIX loopback connect rejected under sandboxed Windows).
    // NIO2 uses Windows I/O completion ports instead of java.nio.Selector, avoiding it.
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> nio2Customizer() {
        return factory -> factory.setProtocol("org.apache.coyote.http11.Http11Nio2Protocol");
    }
}
