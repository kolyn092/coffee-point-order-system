package com.coffeepointordersystem.domain.outbox.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class OutboxEventRetrySchedulerTest {

	@Test
	void retryPendingEvents_retriesUntilPendingEventDoesNotExist() {
		OutboxEventRetryService outboxEventRetryService = mock(OutboxEventRetryService.class);
		when(outboxEventRetryService.publishNextPendingEvent()).thenReturn(true, true, false);
		OutboxEventRetryScheduler scheduler = new OutboxEventRetryScheduler(outboxEventRetryService);

		scheduler.retryPendingEvents();

		then(outboxEventRetryService).should(org.mockito.Mockito.times(3)).publishNextPendingEvent();
	}

	@Test
	void retryPendingEvents_stopsCurrentCycleWhenEventPublicationFails() {
		OutboxEventRetryService outboxEventRetryService = mock(OutboxEventRetryService.class);
		doThrow(new IllegalStateException("Kafka publish failure"))
				.when(outboxEventRetryService)
				.publishNextPendingEvent();
		OutboxEventRetryScheduler scheduler = new OutboxEventRetryScheduler(outboxEventRetryService);

		assertThatCode(scheduler::retryPendingEvents).doesNotThrowAnyException();

		then(outboxEventRetryService).should().publishNextPendingEvent();
	}

}
