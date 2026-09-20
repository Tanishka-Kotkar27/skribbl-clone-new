package com.skribbl.repository;

import com.skribbl.domain.PlayerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlayerRepository extends JpaRepository<PlayerEntity, Long> {

    /**
     * Resolves the live player UUID to its row. This is the bridge between the
     * in-memory game and the database, used every time a round result is saved.
     */
    Optional<PlayerEntity> findByRoomIdAndPlayerUid(Long roomId, String playerUid);

    List<PlayerEntity> findByRoomId(Long roomId);

    /** Final leaderboard, straight from the database. */
    List<PlayerEntity> findByRoomIdOrderByScoreDesc(Long roomId);

    long countByRoomId(Long roomId);
}
