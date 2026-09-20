package com.skribbl;

import com.skribbl.game.Game;
import com.skribbl.game.GamePhase;
import com.skribbl.game.GameSettings;
import com.skribbl.game.Player;
import com.skribbl.game.Room;
import com.skribbl.game.RoomStatus;
import com.skribbl.game.Stroke;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for the game core.
 *
 * <p>These run with no Spring context and no database, which is the payoff of
 * keeping {@link Game} free of transport concerns: the rules that are hardest to
 * debug through a browser are the ones that are cheapest to test here.
 */
class GameLogicTest {

    private Room roomWith(int maxPlayers, int rounds, String... names) {
        GameSettings settings = new GameSettings();
        settings.setMaxPlayers(maxPlayers);
        settings.setRounds(rounds);
        Room room = new Room("TEST01", settings);
        for (int i = 0; i < names.length; i++) {
            room.addPlayer("p" + i, names[i]);
        }
        return room;
    }

    @Nested
    @DisplayName("Room membership")
    class Membership {

        @Test
        @DisplayName("the first player to arrive becomes host")
        void firstPlayerIsHost() {
            Room room = roomWith(8, 3, "Asha", "Bilal");
            assertTrue(room.getPlayer("p0").orElseThrow().isHost());
            assertFalse(room.getPlayer("p1").orElseThrow().isHost());
        }

        @Test
        @DisplayName("a full room refuses further players")
        void roomRefusesWhenFull() {
            Room room = roomWith(2, 3, "Asha", "Bilal");
            assertTrue(room.addPlayer("p9", "Chitra").isEmpty());
            assertEquals(2, room.getPlayerCount());
        }

        @Test
        @DisplayName("when the host leaves, the next player is promoted")
        void hostIsPromotedOnLeave() {
            Room room = roomWith(8, 3, "Asha", "Bilal", "Chitra");
            room.removePlayer("p0");
            assertTrue(room.isHost("p1"));
            assertTrue(room.getPlayer("p1").orElseThrow().isHost());
        }

        @Test
        @DisplayName("a room of one cannot start")
        void singlePlayerCannotStart() {
            Room room = roomWith(8, 3, "Asha");
            assertFalse(room.canStart());
        }
    }

    @Nested
    @DisplayName("Turn rotation")
    class Rotation {

        @Test
        @DisplayName("N rounds with M players produces N*M drawing turns")
        void everyPlayerDrawsEveryRound() {
            Room room = roomWith(8, 2, "Asha", "Bilal", "Chitra");
            Game game = room.getGame();
            game.start();

            List<String> drawers = new ArrayList<>();
            boolean over = false;
            int guard = 0;
            while (!over && guard++ < 50) {
                game.beginTurn();
                drawers.add(game.getDrawerId());
                over = game.advanceTurn();
            }

            assertEquals(6, drawers.size());
            assertEquals(3, new HashSet<>(drawers).size());
            assertEquals(GamePhase.GAME_OVER, game.getPhase());
            assertEquals(RoomStatus.FINISHED, room.getStatus());
        }

        @Test
        @DisplayName("a disconnect mid-game leaves a valid next drawer")
        void disconnectDoesNotStrandTheRotation() {
            Room room = roomWith(8, 3, "Asha", "Bilal", "Chitra");
            Game game = room.getGame();
            game.start();
            game.beginTurn();

            game.removeFromRotation("p1");
            room.removePlayer("p1");
            game.advanceTurn();
            game.beginTurn();

            assertEquals(2, game.getTurnOrder().size());
            assertTrue(room.getPlayer(game.getDrawerId()).isPresent());
        }

        @Test
        @DisplayName("when the active drawer leaves, the player after them is not skipped")
        void activeDrawerLeavingDoesNotSkipSuccessor() {
            Room room = roomWith(8, 2, "Asha", "Bilal", "Chitra");
            Game game = room.getGame();
            game.start();
            game.beginTurn();          // Asha draws
            game.advanceTurn();
            game.beginTurn();          // Bilal draws
            game.confirmWord("rocket");
            assertEquals("p1", game.getDrawerId());

            game.removeFromRotation("p1");
            room.removePlayer("p1");
            game.advanceTurn();
            game.beginTurn();

            assertEquals("p2", game.getDrawerId(), "Chitra must draw next, not be skipped");
        }

        @Test
        @DisplayName("each turn gets a new, increasing turn id")
        void turnIdsIncrease() {
            Room room = roomWith(8, 2, "Asha", "Bilal");
            Game game = room.getGame();
            game.start();
            game.beginTurn();
            long first = game.getTurnId();
            game.advanceTurn();
            game.beginTurn();
            assertTrue(game.getTurnId() > first);
        }
    }

    @Nested
    @DisplayName("Word masking and hints")
    class Words {

        private Game drawingGame(String word) {
            Room room = roomWith(8, 3, "Drawer", "G1", "G2");
            Game game = room.getGame();
            game.start();
            game.beginTurn();
            game.confirmWord(word);
            return game;
        }

        @Test
        @DisplayName("the mask hides letters but keeps spaces")
        void maskKeepsSpaces() {
            assertEquals("___ _____", drawingGame("ice cream").getMaskedWord());
        }

        @Test
        @DisplayName("the mask keeps hyphens visible too")
        void maskKeepsHyphens() {
            assertEquals("_-_____", drawingGame("t-shirt").getMaskedWord());
        }

        @Test
        @DisplayName("each hint reveals exactly one more letter")
        void hintRevealsOneLetter() {
            Game game = drawingGame("rocket");
            game.revealNextHint();
            assertEquals(5, game.getMaskedWord().chars().filter(c -> c == '_').count());
        }

        @Test
        @DisplayName("hints never reveal more than half the word")
        void hintsStopAtHalf() {
            Game game = drawingGame("rocket");
            int guard = 0;
            while (game.revealNextHint().isPresent() && guard++ < 50) {
                // drain
            }
            assertTrue(game.getRevealedIndices().size() <= 3);
        }
    }

    @Nested
    @DisplayName("Word matching")
    class Matching {

        @Test
        @DisplayName("normalisation trims, lowercases, collapses spaces, drops punctuation")
        void normalisation() {
            assertEquals("ice cream", Game.normalise("  Ice   CREAM!  "));
            assertEquals("", Game.normalise(null));
        }

        @Test
        @DisplayName("a sloppily typed but correct guess still counts")
        void sloppyGuessCounts() {
            Room room = roomWith(8, 3, "Drawer", "G1");
            Game game = room.getGame();
            game.start();
            game.beginTurn();
            game.confirmWord("ice cream");

            assertTrue(game.isCorrectGuess(" ICE cream "));
            assertFalse(game.isCorrectGuess("ice cube"));
        }

        @Test
        @DisplayName("one typo is 'close', an exact match and a wild guess are not")
        void closeGuessDetection() {
            Room room = roomWith(8, 3, "Drawer", "G1");
            Game game = room.getGame();
            game.start();
            game.beginTurn();
            game.confirmWord("ice cream");

            assertTrue(game.isCloseGuess("ice creem"));
            assertFalse(game.isCloseGuess("ice cream"));
            assertFalse(game.isCloseGuess("banana"));
        }
    }

    @Nested
    @DisplayName("Scoring")
    class Scoring {

        @Test
        @DisplayName("guessing earlier is worth more, and guessing twice is worth nothing")
        void speedAndOrderMatter() {
            Room room = roomWith(8, 3, "Drawer", "First", "Second");
            Game game = room.getGame();
            game.start();
            game.beginTurn();
            game.confirmWord("rocket");

            Player first = room.getPlayer("p1").orElseThrow();
            Player second = room.getPlayer("p2").orElseThrow();

            int firstPoints = game.registerCorrectGuess(first);
            int secondPoints = game.registerCorrectGuess(second);

            assertTrue(firstPoints > secondPoints);
            assertEquals(0, game.registerCorrectGuess(first));
        }

        @Test
        @DisplayName("the drawer is paid per player who guessed")
        void drawerIsPaidPerGuesser() {
            Room room = roomWith(8, 3, "Drawer", "G1", "G2");
            Game game = room.getGame();
            game.start();
            game.beginTurn();
            game.confirmWord("rocket");

            game.registerCorrectGuess(room.getPlayer("p1").orElseThrow());
            game.registerCorrectGuess(room.getPlayer("p2").orElseThrow());

            assertTrue(game.allGuessersCorrect());
            assertEquals(50, game.awardDrawer());
        }

        @Test
        @DisplayName("the leaderboard is sorted highest first")
        void leaderboardIsSorted() {
            Room room = roomWith(8, 3, "Drawer", "G1", "G2");
            Game game = room.getGame();
            game.start();
            game.beginTurn();
            game.confirmWord("rocket");
            game.registerCorrectGuess(room.getPlayer("p1").orElseThrow());

            List<Player> board = game.getLeaderboard();
            assertTrue(board.get(0).getScore() >= board.get(board.size() - 1).getScore());
            assertEquals(board.get(0).getScore(), game.getWinner().orElseThrow().getScore());
        }
    }

    @Nested
    @DisplayName("Canvas history")
    class Canvas {

        @Test
        @DisplayName("undo pops the most recent stroke and is safe when empty")
        void undoBehaviour() {
            Room room = roomWith(8, 3, "Drawer");
            room.addStroke(new Stroke("s1", "p0", "#ff0000", 4, false));
            room.addStroke(new Stroke("s2", "p0", "#000000", 8, true));

            assertEquals(2, room.getStrokeHistory().size());
            assertEquals("s2", room.undoLastStroke().orElseThrow().getId());
            assertEquals(1, room.getStrokeHistory().size());

            room.clearCanvas();
            assertTrue(room.getStrokeHistory().isEmpty());
            assertTrue(room.undoLastStroke().isEmpty());
        }

        @Test
        @DisplayName("starting a new turn wipes the previous drawing")
        void newTurnClearsCanvas() {
            Room room = roomWith(8, 3, "Drawer", "G1");
            room.addStroke(new Stroke("s1", "p0", "#ff0000", 4, false));
            Game game = room.getGame();
            game.start();
            game.beginTurn();
            assertTrue(room.getStrokeHistory().isEmpty());
        }
    }
}
