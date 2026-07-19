package com.coffeepointordersystem.infra.kafka;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("popular-menu.consumer")
@Validated
public class PopularMenuKafkaConsumerProperties {

	public static final int DEFAULT_CONCURRENCY = 1;
	public static final int MAX_CONCURRENCY = 3;
	private static final int MIN_CONCURRENCY = 1;

	@Min(MIN_CONCURRENCY)
	@Max(MAX_CONCURRENCY)
	private int concurrency = DEFAULT_CONCURRENCY;

	public int getConcurrency() {
		return concurrency;
	}

	public void setConcurrency(int concurrency) {
		this.concurrency = concurrency;
	}

}
