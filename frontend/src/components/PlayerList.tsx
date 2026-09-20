import type { RoomState } from '../types/game';
import Avatar from './Avatar';
import Icon from './Icon';

interface PlayerListProps {
  room: RoomState;
  myId: string;
}

export default function PlayerList({ room, myId }: PlayerListProps) {
  const inGame = room.phase !== 'LOBBY';
  const players = inGame ? [...room.players].sort((a, b) => b.score - a.score) : room.players;

  return (
    <aside className="panel players">
      <div className="panel-header">
        <span className="panel-title">
          Players <span className="count">{room.players.length}/{room.settings.maxPlayers}</span>
        </span>
      </div>

      <ul className="player-rows">
        {players.map((player, index) => {
          const drawing = inGame && room.drawerId === player.id && room.phase !== 'GAME_OVER';
          // After the last turn these flags describe a round that is over; hide them.
          const gotIt = inGame && room.phase !== 'GAME_OVER' && player.guessedCurrentRound;
          const classes = [
            'player-row',
            player.id === myId ? 'me' : '',
            gotIt ? 'guessed' : '',
            player.connected ? '' : 'offline',
          ].join(' ');

          let status = player.id === myId ? 'You' : '';
          if (!player.connected) status = 'Reconnecting…';
          else if (drawing) status = 'Drawing';
          else if (gotIt) status = 'Guessed it';

          return (
            <li key={player.id} className={classes}>
              {inGame && <span className="player-rank">{index + 1}</span>}
              <Avatar seed={player.id} name={player.name} size={34} />
              <div className="player-main">
                <div className="player-name">
                  <span>{player.name}</span>
                  {player.host && (
                    <span title="Host">
                      <Icon name="crown" size={14} />
                    </span>
                  )}
                </div>
                {status && (
                  <div className={`player-status ${drawing ? 'drawing' : gotIt ? 'got-it' : ''}`}>
                    {status}
                  </div>
                )}
              </div>
              {inGame && (
                <div className="player-score nums">
                  <strong>{player.score}</strong>
                  {player.roundScore > 0 && room.phase !== 'GAME_OVER' && (
                    <span className="player-gain">+{player.roundScore}</span>
                  )}
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </aside>
  );
}
