package com.coffeepointordersystem.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UtcClockConfig {

	@Bean
	public Clock utcClock() {
		return Clock.systemUTC();
	}

}
