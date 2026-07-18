package com.coffeepointordersystem.infra.database;

import com.coffeepointordersystem.domain.menu.port.PopularMenuRebuildLock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
public class MySqlPopularMenuRebuildLock implements PopularMenuRebuildLock {

	private static final String LOCK_NAME = "popular-menu-rebuild";

	private final DataSource dataSource;

	public MySqlPopularMenuRebuildLock(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public boolean executeIfAcquired(Runnable action) {
		try (Connection connection = dataSource.getConnection()) {
			if (!tryAcquire(connection)) {
				return false;
			}

			try {
				action.run();
			} finally {
				release(connection);
			}

			return true;
		} catch (SQLException exception) {
			throw new IllegalStateException("인기 메뉴 Redis 재구성 잠금을 처리하지 못했습니다.", exception);
		}
	}

	private boolean tryAcquire(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
			statement.setString(1, LOCK_NAME);
			try (ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next() && resultSet.getInt(1) == 1;
			}
		}
	}

	private void release(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
			statement.setString(1, LOCK_NAME);
			statement.executeQuery();
		}
	}

}
