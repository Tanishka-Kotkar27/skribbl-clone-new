package com.skribbl.repository;

import com.skribbl.domain.RoomEntity;
import com.skribbl.game.RoomStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface RoomRepository extends JpaRepository<RoomEntity, Long> {

    /** Rooms are looked up by their shareable code far more often than by id. */
    Optional<RoomEntity> findByCode(String code);

    boolean existsByCode(String code);

    List<RoomEntity> findByStatus(RoomStatus status);

    /** Supports a future cleanup job for rooms abandoned without finishing. */
    List<RoomEntity> findByStatusAndCreatedAtBefore(RoomStatus status, Instant cutoff);
}
