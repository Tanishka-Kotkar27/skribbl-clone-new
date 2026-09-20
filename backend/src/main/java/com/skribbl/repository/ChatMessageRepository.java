package com.skribbl.repository;

import com.skribbl.domain.ChatMessageEntity;
import com.skribbl.domain.MessageType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    List<ChatMessageEntity> findByRoomIdOrderByCreatedAtAsc(Long roomId);

    List<ChatMessageEntity> findByRoundIdOrderByCreatedAtAsc(Long roundId);

    /** Used to reconstruct who guessed correctly, and in what order. */
    List<ChatMessageEntity> findByRoundIdAndTypeOrderByCreatedAtAsc(Long roundId, MessageType type);

    long countByRoomId(Long roomId);
}
