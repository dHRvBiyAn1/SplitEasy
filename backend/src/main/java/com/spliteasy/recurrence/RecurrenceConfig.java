package com.spliteasy.recurrence;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
class RecurrenceConfig {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
