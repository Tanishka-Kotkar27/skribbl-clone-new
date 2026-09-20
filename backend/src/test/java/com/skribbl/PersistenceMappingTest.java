package com.skribbl;

import com.skribbl.domain.ChatMessageEntity;
import com.skribbl.domain.MessageType;
import com.skribbl.domain.PlayerEntity;
import com.skribbl.domain.RoomEntity;
import com.skribbl.domain.RoundEntity;
import com.skribbl.domain.RoundStatus;
import com.skribbl.domain.WordEntity;
import com.skribbl.game.GameSettings;
import com.skribbl.game.RoomStatus;
import com.skribbl.game.WordMode;
import com.skribbl.repository.ChatMessageRepository;
import com.skribbl.repository.PlayerRepository;
import com.skribbl.repository.RoomRepository;
import com.skribbl.repository.RoundRepository;
import com.skribbl.repository.WordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the JPA mapping against a real (in-memory) database.
 *
 * <p>Runs on H2 rather than MySQL so {@code mvn test} works on a machine with no
 * database running — which is what makes it safe to run in CI, and on a laptop
 * where Docker is being uncooperative.
 *
 * <p>The tests that matter most here are the two that would fail silently in
 * production: the settings JSON round-trip, and the foreign keys actually
 * linking rows rather than quietly writing nulls.
 */
@DataJpaTest
@ActiveProfiles("test")
class PersistenceMappingTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private RoundRepository roundRepository;

    @Autowired
    private WordRepository wordRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    private RoomEntity persistedRoom(String code) {
        RoomEntity room = new RoomEntity(code, "Asha", new GameSettings());
        room.setHostUid("uid-host");
        return roomRepository.save(room);
    }

    @Test
    @DisplayName("a room round-trips with its code, status and generated timestamp")
    void roomRoundTrips() {
        RoomEntity saved = persistedRoom("ABC123");
        entityManager.flush();
        entityManager.clear();

        RoomEntity found = roomRepository.findByCode("ABC123").orElseThrow();
        assertNotNull(found.getId());
        assertEquals("Asha", found.getHostName());
        assertEquals(RoomStatus.WAITING, found.getStatus());
        assertNotNull(found.getCreatedAt(), "@PrePersist should set createdAt");
        assertEquals(saved.getId(), found.getId());
        assertTrue(roomRepository.existsByCode("ABC123"));
    }

    @Test
    @DisplayName("settings survive the JSON converter unchanged")
    void settingsSurviveJsonConversion() {
        GameSettings settings = new GameSettings();
        settings.setRounds(7);
        settings.setDrawTimeSeconds(120);
        settings.setMaxPlayers(12);
        settings.setHints(4);
        settings.setWordChoices(5);
        settings.setWordMode(WordMode.HIDDEN);
        settings.setPrivate(false);
        settings.setCustomWords(List.of("rangoli", "auto rickshaw"));

        RoomEntity room = new RoomEntity("JSON01", "Bilal", settings);
        roomRepository.save(room);
        entityManager.flush();
        entityManager.clear();

        GameSettings loaded = roomRepository.findByCode("JSON01").orElseThrow().getSettings();
        assertEquals(7, loaded.getRounds());
        assertEquals(120, loaded.getDrawTimeSeconds());
        assertEquals(12, loaded.getMaxPlayers());
        assertEquals(4, loaded.getHints());
        assertEquals(5, loaded.getWordChoices());
        assertEquals(WordMode.HIDDEN, loaded.getWordMode());
        assertFalse(loaded.isPrivate());
        assertEquals(List.of("rangoli", "auto rickshaw"), loaded.getCustomWords());
    }

    @Test
    @DisplayName("players link to their room and can be found by their live UUID")
    void playersLinkToRoom() {
        RoomEntity room = persistedRoom("PLAY01");
        playerRepository.save(new PlayerEntity(room, "uid-host", "Asha", true));
        playerRepository.save(new PlayerEntity(room, "uid-2", "Bilal", false));
        entityManager.flush();
        entityManager.clear();

        assertEquals(2, playerRepository.countByRoomId(room.getId()));

        PlayerEntity found = playerRepository
                .findByRoomIdAndPlayerUid(room.getId(), "uid-2")
                .orElseThrow();
        assertEquals("Bilal", found.getName());
        assertFalse(found.isHost());
        assertNotNull(found.getJoinedAt());
    }

    @Test
    @DisplayName("the leaderboard query sorts by score, highest first")
    void leaderboardIsSortedByScore() {
        RoomEntity room = persistedRoom("SCORE1");
        PlayerEntity low = new PlayerEntity(room, "uid-1", "Low", true);
        low.setScore(40);
        PlayerEntity high = new PlayerEntity(room, "uid-2", "High", false);
        high.setScore(310);
        PlayerEntity mid = new PlayerEntity(room, "uid-3", "Mid", false);
        mid.setScore(150);
        playerRepository.saveAll(List.of(low, high, mid));
        entityManager.flush();
        entityManager.clear();

        List<PlayerEntity> board = playerRepository.findByRoomIdOrderByScoreDesc(room.getId());
        assertEquals(List.of("High", "Mid", "Low"),
                board.stream().map(PlayerEntity::getName).toList());
    }

    @Test
    @DisplayName("rounds record the drawer, the word and the turn position")
    void roundsRecordTheTurn() {
        RoomEntity room = persistedRoom("ROUND1");
        PlayerEntity drawer = playerRepository.save(
                new PlayerEntity(room, "uid-host", "Asha", true));

        RoundEntity round = new RoundEntity(room, drawer, 2, 1);
        round.setWord("ice cream");
        round.setStatus(RoundStatus.ALL_GUESSED);
        round.setCorrectGuessCount(3);
        roundRepository.save(round);
        entityManager.flush();
        entityManager.clear();

        RoundEntity found = roundRepository
                .findByRoomIdAndRoundNumberAndTurnIndex(room.getId(), 2, 1)
                .orElseThrow();
        assertEquals("ice cream", found.getWord());
        assertEquals(RoundStatus.ALL_GUESSED, found.getStatus());
        assertEquals(3, found.getCorrectGuessCount());
        assertEquals("Asha", found.getDrawer().getName());
    }

    @Test
    @DisplayName("turns come back in the order they were played")
    void turnsAreOrdered() {
        RoomEntity room = persistedRoom("ORDER1");
        PlayerEntity drawer = playerRepository.save(
                new PlayerEntity(room, "uid-host", "Asha", true));

        // Saved deliberately out of order.
        roundRepository.save(new RoundEntity(room, drawer, 2, 0));
        roundRepository.save(new RoundEntity(room, drawer, 1, 1));
        roundRepository.save(new RoundEntity(room, drawer, 1, 0));
        entityManager.flush();
        entityManager.clear();

        List<RoundEntity> rounds =
                roundRepository.findByRoomIdOrderByRoundNumberAscTurnIndexAsc(room.getId());
        assertEquals(3, rounds.size());
        assertEquals(1, rounds.get(0).getRoundNumber());
        assertEquals(0, rounds.get(0).getTurnIndex());
        assertEquals(1, rounds.get(1).getRoundNumber());
        assertEquals(1, rounds.get(1).getTurnIndex());
        assertEquals(2, rounds.get(2).getRoundNumber());
    }

    @Test
    @DisplayName("chat and guesses share one table and filter by type")
    void chatAndGuessesShareOneTable() {
        RoomEntity room = persistedRoom("CHAT01");
        PlayerEntity player = playerRepository.save(
                new PlayerEntity(room, "uid-1", "Asha", true));
        RoundEntity round = roundRepository.save(new RoundEntity(room, player, 1, 0));

        chatMessageRepository.save(
                new ChatMessageEntity(room, round, player, "is it a cat?", MessageType.GUESS));
        ChatMessageEntity correct =
                new ChatMessageEntity(room, round, player, "dog", MessageType.CORRECT_GUESS);
        correct.setPointsAwarded(180);
        chatMessageRepository.save(correct);
        chatMessageRepository.save(
                new ChatMessageEntity(room, round, null, "Round over", MessageType.SYSTEM));
        entityManager.flush();
        entityManager.clear();

        assertEquals(3, chatMessageRepository.countByRoomId(room.getId()));

        List<ChatMessageEntity> correctOnes = chatMessageRepository
                .findByRoundIdAndTypeOrderByCreatedAtAsc(round.getId(), MessageType.CORRECT_GUESS);
        assertEquals(1, correctOnes.size());
        assertEquals(180, correctOnes.get(0).getPointsAwarded());
        assertEquals("Asha", correctOnes.get(0).getPlayer().getName());
    }

    @Test
    @DisplayName("system messages are allowed to have no author")
    void systemMessagesHaveNoPlayer() {
        RoomEntity room = persistedRoom("SYS001");
        chatMessageRepository.save(
                new ChatMessageEntity(room, null, null, "Asha joined", MessageType.SYSTEM));
        entityManager.flush();
        entityManager.clear();

        ChatMessageEntity found =
                chatMessageRepository.findByRoomIdOrderByCreatedAtAsc(room.getId()).get(0);
        assertEquals(MessageType.SYSTEM, found.getType());
        assertNotNull(found.getCreatedAt());
    }

    @Test
    @DisplayName("words are unique by text and groupable by category")
    void wordsAreUniqueAndCategorised() {
        wordRepository.save(new WordEntity("elephant", "animals", 2));
        wordRepository.save(new WordEntity("pizza", "food", 1));
        wordRepository.save(new WordEntity("samosa", "food", 2));
        entityManager.flush();
        entityManager.clear();

        assertTrue(wordRepository.existsByText("pizza"));
        assertFalse(wordRepository.existsByText("nonexistent"));
        assertEquals(2, wordRepository.findByCategory("food").size());
        assertEquals(List.of("animals", "food"), wordRepository.findDistinctCategories());
    }
}
