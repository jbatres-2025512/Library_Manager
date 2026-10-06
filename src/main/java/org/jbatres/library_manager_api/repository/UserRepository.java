package org.jbatres.library_manager_api.repository;

import org.jbatres.library_manager_api.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * SELECT ... FOR UPDATE on the reader row. Serializes concurrent loan requests of the
     * SAME reader so the "max 3 active loans" invariant cannot be broken by a race.
     * Must be called inside a transaction. Lock waits are bounded by PostgreSQL lock_timeout.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}