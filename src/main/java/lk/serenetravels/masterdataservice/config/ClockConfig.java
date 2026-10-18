package lk.serenetravels.masterdataservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfig {

    // Rates are dated in the business's own calendar, not the server's, so "today" must not
    // shift with wherever this ends up deployed (e.g. a UTC EC2 box).
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Colombo"));
    }
}
