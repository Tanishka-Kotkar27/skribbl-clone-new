package com.skribbl.repository;

import com.skribbl.domain.RoundEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoundRepository extends JpaRepository<RoundEntity, Long> {

    /** Every turn played in a room, in the order they happened. */
    List<RoundEntity> findByRoomIdOrderByRoundNumberAscTurnIndexAsc(Long roomId);

    Optional<RoundEntity> findByRoomIdAndRoundNumberAndTurnIndex(
            Long roomId, int roundNumber, int turnIndex);

    long countByRoomId(Long roomId);
}
