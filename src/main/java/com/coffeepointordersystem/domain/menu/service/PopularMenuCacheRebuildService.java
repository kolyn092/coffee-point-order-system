package com.coffeepointordersystem.domain.menu.service;

import com.coffeepointordersystem.domain.menu.exception.PopularMenuUnavailableException;
import com.coffeepointordersystem.domain.menu.port.PopularMenuCache;
import com.coffeepointordersystem.domain.menu.port.PopularMenuRebuildLock;
import com.coffeepointordersystem.domain.menu.repository.PopularMenuQueryRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PopularMenuCacheRebuildService {

	private static final Logger log = LoggerFactory.getLogger(PopularMenuCacheRebuildService.class);

	private final PopularMenuCache popularMenuCache;
	private final PopularMenuQueryRepository popularMenuQueryRepository;
	private final PopularMenuRebuildLock popularMenuRebuildLock;

	public PopularMenuCacheRebuildService(
			PopularMenuCache popularMenuCache,
			PopularMenuQueryRepository popularMenuQueryRepository,
			PopularMenuRebuildLock popularMenuRebuildLock
	) {
		this.popularMenuCache = popularMenuCache;
		this.popularMenuQueryRepository = popularMenuQueryRepository;
		this.popularMenuRebuildLock = popularMenuRebuildLock;
	}

	public void rebuildIfPossible(LocalDate from, LocalDate to) {
		try {
			popularMenuRebuildLock.executeIfAcquired(() -> rebuild(from, to));
		} catch (RuntimeException exception) {
			log.warn("인기 메뉴 Redis 재구성에 실패했습니다.", exception);
		}
	}

	private void rebuild(LocalDate from, LocalDate to) {
		String ownerToken = UUID.randomUUID().toString();
		boolean rebuilding = popularMenuCache.tryStartRebuild(ownerToken);
		if (!rebuilding) {
			return;
		}

		try {
			popularMenuCache.rebuild(
					from,
					to,
					popularMenuQueryRepository.findCompletedOrders(from, to),
					ownerToken
			);
		} finally {
			try {
				popularMenuCache.releaseRebuild(ownerToken);
			} catch (PopularMenuUnavailableException exception) {
				log.warn("인기 메뉴 Redis 재구성 표식을 해제하지 못했습니다.", exception);
			}
		}
	}

}
