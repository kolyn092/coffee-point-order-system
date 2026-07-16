package com.coffeepointordersystem.domain.point.repository;

import com.coffeepointordersystem.domain.point.entity.PointAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointAccountRepository extends JpaRepository<PointAccount, String> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT pointAccount FROM PointAccount pointAccount WHERE pointAccount.userId = :userId")
	Optional<PointAccount> findByUserIdForUpdate(@Param("userId") String userId);

}
