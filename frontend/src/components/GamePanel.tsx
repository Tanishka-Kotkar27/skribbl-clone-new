import type { ReactNode } from 'react';
import type { GameOverPayload, RoundEndPayload, RoundStartPayload } from '../types/events';
import type { PlayerView, RoomState } from '../types/game';
import Avatar from './Avatar';
import CanvasBoard from './CanvasBoard';
import Icon from './Icon';

interface GamePanelProps {
  room: RoomState;
  myId: string;
  isHost: boolean;
  remaining: number;
  roundInfo: RoundStartPayload | null;
  /** Only set for the drawer while choosing. */
  choices: string[] | null;
  /** The word, once this player may see it: the drawer, or a guesser who got it. */
  myWord: string | null;
  roundEnd: RoundEndPayload | null;
  gameOver: GameOverPayload | null;
  busy: boolean;
  onChooseWord: (word: string) => void;
  onPlayAgain: () => void;
}

/**
 * The canvas stays mounted for the whole game; word choice, round results and
 * final standings are overlays on top of it, so the finished drawing stays
 * visible while the answer is shown.
 */
export default function GamePanel(props: GamePanelProps) {
  const { room, myId, remaining, roundInfo, choices, myWord, roundEnd, gameOver } = props;

  const isDrawer = room.drawerId === myId;
  const drawer = room.players.find((p) => p.id === room.drawerId);
  const drawerName = roundInfo?.drawerName ?? drawer?.name ?? 'Someone';
  const drawing = room.phase === 'DRAWING';
  const canDraw = isDrawer && drawing && !gameOver;

  let overlay: ReactNode = null;
  if (gameOver) {
    overlay = <GameOver {...props} gameOver={gameOver} />;
  } else if (roundEnd && !drawing && room.phase !== 'CHOOSING') {
    overlay = <RoundResult room={room} roundEnd={roundEnd} />;
  } else if (room.phase === 'CHOOSING') {
    overlay =
      isDrawer && choices ? (
        <div className="overlay">
          <div className="overlay-eyebrow">Your turn</div>
          <h2>Choose a word to draw</h2>
          <div className="word-options">
            {choices.map((word) => (
              <button key={word} className="word-option" onClick={() => props.onChooseWord(word)}>
                {word}
              </button>
            ))}
          </div>
          <p className="overlay-note">
            One is picked for you after {roundInfo?.choiceTimeoutSeconds ?? 15} seconds.
          </p>
        </div>
      ) : (
        <div className="overlay waiting">
          {drawer && <Avatar seed={drawer.id} name={drawer.name} size={56} />}
          <h2>{drawerName} is choosing a word</h2>
          <p className="muted">Get ready to guess.</p>
        </div>
      );
  }

  return (
    <section className="panel stage">
      <div className="stage-bar">
        <span className="pill round">
          Round {room.currentRound} of {room.totalRounds}
        </span>

        <div className="word">
          {drawing && isDrawer && myWord && (
            <>
              <span className="word-caption">Draw this</span>
              <span className="word-secret">{myWord}</span>
            </>
          )}
          {drawing && !isDrawer && myWord && (
            <>
              <span className="word-caption">You got it</span>
              <span className="word-secret got-it">{myWord}</span>
            </>
          )}
          {drawing && !myWord && (
            <>
              <span className="word-caption">{drawerName} is drawing</span>
              <Blanks masked={room.maskedWord} />
            </>
          )}
        </div>

        {drawing ? (
          <span className={`timer ${remaining <= 10 ? 'urgent' : ''}`} aria-label={`${remaining} seconds left`}>
            <Icon name="clock" size={17} />
            {remaining}
          </span>
        ) : (
          <span />
        )}
      </div>

      <CanvasBoard canDraw={canDraw} myId={myId}>
        {overlay}
      </CanvasBoard>
    </section>
  );
}

/** "ice cream" masked as "___ _____" becomes underlined slots, with the letter counts. */
function Blanks({ masked }: { masked: string }) {
  if (!masked) return null;
  if (masked === '?') {
    return <span className="blanks">? ? ?</span>;
  }
  const counts = masked.split(' ').map((part) => part.length);
  return (
    <span className="blanks" aria-label={`${counts.join(' and ')} letters`}>
      {masked.split('').map((ch, i) =>
        ch === ' ' ? (
          <span key={i} className="blank-gap" />
        ) : ch === '-' ? (
          <span key={i}>-</span>
        ) : (
          <span key={i} className="blank">
            {ch === '_' ? ' ' : ch}
          </span>
        ),
      )}
      <span className="blank-count">{counts.join(' ')}</span>
    </span>
  );
}

function RoundResult({ room, roundEnd }: { room: RoomState; roundEnd: RoundEndPayload }) {
  const heading: Record<RoundEndPayload['reason'], string> = {
    TIME_UP: "Time's up",
    ALL_GUESSED: 'Everyone got it',
    DRAWER_LEFT: 'The drawer left',
  };
  const rows = [...roundEnd.players].sort((a, b) => b.roundScore - a.roundScore);
  const next = room.players.find((p) => p.id === roundEnd.nextDrawerId);

  return (
    <div className="overlay">
      <div className="overlay-eyebrow">{heading[roundEnd.reason]}</div>
      <p className="subtle">The word was</p>
      <span className="reveal-word">{roundEnd.word || '—'}</span>
      <ul className="gains">
        {rows.map((p) => (
          <li key={p.id}>
            <Avatar seed={p.id} name={p.name} size={26} />
            <span className="name">{p.name}</span>
            <span className={p.roundScore > 0 ? 'plus nums' : 'zero nums'}>+{p.roundScore}</span>
          </li>
        ))}
      </ul>
      <p className="overlay-note">
        {roundEnd.gameOver ? 'Final results next' : next ? `${next.name} draws next` : ''}
      </p>
    </div>
  );
}

function GameOver(props: GamePanelProps & { gameOver: GameOverPayload }) {
  const { gameOver, isHost, busy, onPlayAgain } = props;
  const winners: PlayerView[] = gameOver.winners;
  const headline =
    winners.length === 0
      ? 'Game over'
      : winners.length === 1
        ? `${winners[0].name} wins`
        : `${winners.map((w) => w.name).join(' and ')} tie`;

  return (
    <div className="overlay">
      <div className="overlay-eyebrow">Final results</div>
      <div className="winner">
        {winners[0] && <Avatar seed={winners[0].id} name={winners[0].name} size={64} />}
        <h2>{headline}</h2>
      </div>
      <ol className="standings">
        {gameOver.leaderboard.map((p, i) => (
          <li key={p.id} className={i === 0 ? 'first' : ''}>
            <span className="pos nums">{i + 1}</span>
            <Avatar seed={p.id} name={p.name} size={26} />
            <span className="name">{p.name}</span>
            <span className="pts nums">{p.score}</span>
          </li>
        ))}
      </ol>
      {isHost ? (
        <button className="btn btn-primary btn-lg" onClick={onPlayAgain} disabled={busy}>
          <Icon name="play" size={16} />
          {busy ? 'Starting…' : 'Play again'}
        </button>
      ) : (
        <p className="muted">Waiting for the host to start another game.</p>
      )}
    </div>
  );
}
