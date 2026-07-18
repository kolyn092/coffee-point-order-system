package com.coffeepointordersystem.domain.menu.port;

public interface PopularMenuRebuildLock {

	boolean executeIfAcquired(Runnable action);

}
