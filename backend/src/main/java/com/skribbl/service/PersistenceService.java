package com.skribbl.service;

import com.skribbl.domain.ChatMessageEntity;
import com.skribbl.domain.MessageType;
import com.skribbl.domain.PlayerEntity;
import com.skribbl.domain.RoomEntity;
import com.skribbl.domain.RoundEntity;
import com.skribbl.domain.RoundStatus;
import com.skribbl.game.Player;
import com.skribbl.game.Room;
import com.skribbl.game.RoomStatus;
import com.skribbl.repository.ChatMessageRepository;
import com.skribbl.repository.PlayerRepository;
import com.skribbl.repository.RoomRepository;
import com.skribbl.repository.RoundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The single bridge between the live game and the database.
 *
 * <p>Every write to MySQL goes through here, which keeps one rule enforceable in
 * one place: <strong>persistence happens at lifecycle boundaries, never in the hot
 * path</strong>. Room created, player joined, round started, round ended, game
 * over — those are the write points. Strokes and timer ticks, which happen tens
 * of times a second, are never written.
 *
 * <p>Failures here are logged and swallowed rather than propagated. A database
 * hiccup should not kill a game in progress: the in-memory state is the source
 * of truth during play, so a lost history row costs a row of analytics, whereas
 * a thrown exception mid-round would drop every player in the room.
 */
@Service
public class PersistenceService {

    private static final Logger log = LoggerFactory.getLogger(PersistenceService.class);

    private final RoomRepository roomRepository;
    private final PlayerRepository playerRepository;
    private final RoundRepository roundRepository;
    private final ChatMessageRepository chatMessageRepository;

    public PersistenceService(RoomRepository roomRepository,
                              PlayerRepository playerRepository,
                              RoundRepository roundRepository,
                              ChatMessageRepository chatMessageRepository) {
        this.roomRepository = roomRepository;
        this.playerRepository = playerRepository;
        this.roundRepository = roundRepository;
        this.chatMessageRepository = chatMessageRepository;
    }

    // ------------------------------------------------------------ room and players

    /**
     * Records a newly created room and its host.
     *
     * @return the database id, which the caller stores on the live room so later
     *         writes do not have to look the room up by code
     */
    @Transactional
    public Optional<Long> saveNewRoom(Room room, Player host) {
        try {
            RoomEntity entity = new RoomEntity(room.getCode(), host.getName(), room.getSettings());
            entity.setHostUid(host.getId());
            entity.setStatus(RoomStatus.WAITING);
            RoomEntity saved = roomRepository.save(entity);

            playerRepository.save(new PlayerEntity(saved, host.getId(), host.getName(), true));

            log.debug("Persisted room {} (id {})", room.getCode(), saved.getId());
            return Optional.of(saved.getId());
        } catch (RuntimeException e) {
            log.error("Could not persist room {}", room.getCode(), e);
            return Optional.empty();
        }
    }

    /** Records a player joining an existing room. */
    @Transactional
    public void savePlayerJoined(Room room, Player player) {
        Long roomId = room.getPersistentId();
        if (roomId == null) {
            return;
        }
        try {
            RoomEntity roomRef = roomRepository.getReferenceById(roomId);
            playerRepository.save(
                    new PlayerEntity(roomRef, player.getId(), player.getName(), player.isHost()));
        } catch (RuntimeException e) {
            log.error("Could not persist player {} in room {}", player.getName(), room.getCode(), e);
        }
    }

    /** Marks a player as having left, keeping the row for history. */
    @Transactional
    public void savePlayerLeft(Room room, String playerUid) {
        Long roomId = room.getPersistentId();
        if (roomId == null) {
            return;
        }
        try {
            playerRepository.findByRoomIdAndPlayerUid(roomId, playerUid).ifPresent(entity -> {
                entity.setLeftAt(Instant.now());
                playerRepository.save(entity);
            });
        } catch (RuntimeException e) {
            log.error("Could not mark player {} as left", playerUid, e);
        }
    }

    /** Records the host changing after the original host disconnected. */
    @Transactional
    public void saveHostChanged(Room room, String newHostUid) {
        Long roomId = room.getPersistentId();
        if (roomId == null || newHostUid == null) {
            return;
        }
        try {
            for (PlayerEntity player : playerRepository.findByRoomId(roomId)) {
                player.setHost(player.getPlayerUid().equals(newHostUid));
            }
            roomRepository.findById(roomId).ifPresent(entity -> {
                entity.setHostUid(newHostUid);
                roomRepository.save(entity);
            });
        } catch (RuntimeException e) {
            log.error("Could not update host for room {}", room.getCode(), e);
        }
    }

    // ------------------------------------------------------------------- game flow

    @Transactional
    public void saveGameStarted(Room room) {
        updateRoom(room, entity -> {
            entity.setStatus(RoomStatus.IN_PROGRESS);
            entity.setStartedAt(Instant.now());
        });
    }

    /**
     * Opens a round row when a drawing turn begins.
     *
     * @return the round's database id, to be passed back to {@link #saveRoundEnded}
     */
    @Transactional
    public Optional<Long> saveRoundStarted(Room room, String drawerUid,
                                           int roundNumber, int turnIndex) {
        Long roomId = room.getPersistentId();
        if (roomId == null) {
            return Optional.empty();
        }
        try {
            RoomEntity roomRef = roomRepository.getReferenceById(roomId);
            PlayerEntity drawer = playerRepository
                    .findByRoomIdAndPlayerUid(roomId, drawerUid)
                    .orElse(null);

            RoundEntity round = new RoundEntity(roomRef, drawer, roundNumber, turnIndex);
            round.setStatus(RoundStatus.CHOOSING);
            round.setStartedAt(Instant.now());
            return Optional.of(roundRepository.save(round).getId());
        } catch (RuntimeException e) {
            log.error("Could not persist round start for room {}", room.getCode(), e);
            return Optional.empty();
        }
    }

    /** Records the word once the drawer has chosen it. */
    @Transactional
    public void saveWordChosen(Long roundId, String word) {
        if (roundId == null) {
            return;
        }
        try {
            roundRepository.findById(roundId).ifPresent(round -> {
                round.setWord(word);
                round.setStatus(RoundStatus.DRAWING);
                roundRepository.save(round);
            });
        } catch (RuntimeException e) {
            log.error("Could not persist chosen word for round {}", roundId, e);
        }
    }

    /**
     * Closes a round and writes the scores as they stand.
     *
     * <p>Scores are synced here rather than on every correct guess: one write per
     * round instead of one per guess, and the in-memory values are authoritative
     * until the round is over anyway.
     */
    @Transactional
    public void saveRoundEnded(Room room, Long roundId, RoundStatus status, int correctGuessCount) {
        Long roomId = room.getPersistentId();
        if (roomId == null) {
            return;
        }
        try {
            if (roundId != null) {
                roundRepository.findById(roundId).ifPresent(round -> {
                    round.setStatus(status);
                    round.setCorrectGuessCount(correctGuessCount);
                    round.setEndedAt(Instant.now());
                    roundRepository.save(round);
                });
            }
            syncScores(room);
        } catch (RuntimeException e) {
            log.error("Could not persist round end for room {}", room.getCode(), e);
        }
    }

    /**
     * Closes a round using a score snapshot taken under the room lock.
     *
     * <p>This is the variant the game engine uses. It runs on a background
     * writer thread, where reading live scores would race with the game; the
     * snapshot is what the scores were at the instant the round ended.
     */
    @Transactional
    public void saveRoundEnded(Room room, Long roundId, RoundStatus status,
                               int correctGuessCount, Map<String, Integer> scores) {
        Long roomId = room.getPersistentId();
        if (roomId == null) {
            return;
        }
        try {
            if (roundId != null) {
                roundRepository.findById(roundId).ifPresent(round -> {
                    round.setStatus(status);
                    round.setCorrectGuessCount(correctGuessCount);
                    round.setEndedAt(Instant.now());
                    roundRepository.save(round);
                });
            }
            syncScores(roomId, scores);
        } catch (RuntimeException e) {
            log.error("Could not persist round end for room {}", room.getCode(), e);
        }
    }

    /** Game over, using a score snapshot. See {@link #saveRoundEnded(Room, Long, RoundStatus, int, Map)}. */
    @Transactional
    public void saveGameOver(Room room, Map<String, Integer> scores) {
        Long roomId = room.getPersistentId();
        if (roomId == null) {
            return;
        }
        try {
            syncScores(roomId, scores);
            updateRoom(room, entity -> {
                entity.setStatus(RoomStatus.FINISHED);
                entity.setFinishedAt(Instant.now());
            });
        } catch (RuntimeException e) {
            log.error("Could not persist game over for room {}", room.getCode(), e);
        }
    }

    /** Writes a score snapshot onto the player rows. */
    @Transactional
    public void syncScores(Long roomId, Map<String, Integer> scores) {
        List<PlayerEntity> entities = playerRepository.findByRoomId(roomId);
        for (PlayerEntity entity : entities) {
            Integer score = scores.get(entity.getPlayerUid());
            if (score != null) {
                entity.setScore(score);
            }
        }
        playerRepository.saveAll(entities);
    }

    @Transactional
    public void saveGameOver(Room room) {
        try {
            syncScores(room);
            updateRoom(room, entity -> {
                entity.setStatus(RoomStatus.FINISHED);
                entity.setFinishedAt(Instant.now());
            });
        } catch (RuntimeException e) {
            log.error("Could not persist game over for room {}", room.getCode(), e);
        }
    }

    /** Copies current in-memory scores onto the player rows. */
    @Transactional
    public void syncScores(Room room) {
        Long roomId = room.getPersistentId();
        if (roomId == null) {
            return;
        }
        List<PlayerEntity> entities = playerRepository.findByRoomId(roomId);
        for (PlayerEntity entity : entities) {
            room.getPlayer(entity.getPlayerUid())
                    .ifPresent(live -> entity.setScore(live.getScore()));
        }
        playerRepository.saveAll(entities);
    }

    // ---------------------------------------------------------------------- chat

    /** Stores a chat line, guess or system notice. */
    @Transactional
    public void saveMessage(Room room, Long roundId, String playerUid,
                            String content, MessageType type, int pointsAwarded) {
        Long roomId = room.getPersistentId();
        if (roomId == null) {
            return;
        }
        try {
            RoomEntity roomRef = roomRepository.getReferenceById(roomId);
            RoundEntity roundRef = roundId == null
                    ? null
                    : roundRepository.findById(roundId).orElse(null);
            PlayerEntity playerRef = playerUid == null
                    ? null
                    : playerRepository.findByRoomIdAndPlayerUid(roomId, playerUid).orElse(null);

            ChatMessageEntity message =
                    new ChatMessageEntity(roomRef, roundRef, playerRef, truncate(content), type);
            message.setPointsAwarded(pointsAwarded);
            chatMessageRepository.save(message);
        } catch (RuntimeException e) {
            log.error("Could not persist message in room {}", room.getCode(), e);
        }
    }

    // ------------------------------------------------------------------- helpers

    private void updateRoom(Room room, java.util.function.Consumer<RoomEntity> mutation) {
        Long roomId = room.getPersistentId();
        if (roomId == null) {
            return;
        }
        try {
            roomRepository.findById(roomId).ifPresent(entity -> {
                mutation.accept(entity);
                roomRepository.save(entity);
            });
        } catch (RuntimeException e) {
            log.error("Could not update room {}", room.getCode(), e);
        }
    }

    /** The content column is 500 chars; a long paste should not throw. */
    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 500 ? content : content.substring(0, 500);
    }
}
