import { useState } from 'react';
import type { RoomState } from '../types/game';
import Icon from './Icon';

interface LobbyPanelProps {
  room: RoomState;
  isHost: boolean;
  busy: boolean;
  onStart: () => void;
}

const MODE_LABEL: Record<RoomState['settings']['wordMode'], string> = {
  NORMAL: 'Normal',
  HIDDEN: 'Hidden',
  COMBINATION: 'Combination',
};

export default function LobbyPanel({ room, isHost, busy, onStart }: LobbyPanelProps) {
  const [copied, setCopied] = useState<'code' | 'link' | null>(null);
  const connected = room.players.filter((p) => p.connected).length;
  const canStart = connected >= 2;
  const host = room.players.find((p) => p.id === room.hostId);
  const s = room.settings;

  async function copy(kind: 'code' | 'link') {
    const text = kind === 'code' ? room.code : `${window.location.origin}/join/${room.code}`;
    try {
      await navigator.clipboard.writeText(text);
      setCopied(kind);
      setTimeout(() => setCopied(null), 1800);
    } catch {
      // Clipboard can be blocked on plain-http origins; the code is on screen anyway.
    }
  }

  return (
    <section className="panel lobby">
      <div className="lobby-head">
        <h2>Waiting for players</h2>
        <p className="subtle">
          {canStart
            ? `${connected} players are here. Invite more, or start when you're ready.`
            : 'You need at least one more player to start. Send them the code below.'}
        </p>
      </div>

      <div className="invite">
        <div>
          <div className="invite-label">Room code</div>
          <div className="invite-code">{room.code}</div>
        </div>
        <div className="invite-actions">
          <button type="button" className="btn btn-secondary" onClick={() => copy('code')}>
            <Icon name={copied === 'code' ? 'check' : 'copy'} size={16} />
            {copied === 'code' ? 'Copied' : 'Copy code'}
          </button>
          <button type="button" className="btn btn-secondary" onClick={() => copy('link')}>
            <Icon name={copied === 'link' ? 'check' : 'link'} size={16} />
            {copied === 'link' ? 'Copied' : 'Copy link'}
          </button>
        </div>
      </div>

      <dl className="settings-summary">
        <div className="setting"><dt>Rounds</dt><dd>{s.rounds}</dd></div>
        <div className="setting"><dt>Draw time</dt><dd>{s.drawTimeSeconds}s</dd></div>
        <div className="setting"><dt>Max players</dt><dd>{s.maxPlayers}</dd></div>
        <div className="setting"><dt>Word choices</dt><dd>{s.wordChoices}</dd></div>
        <div className="setting"><dt>Hints</dt><dd>{s.hints === 0 ? 'Off' : s.hints}</dd></div>
        <div className="setting">
          <dt>Mode</dt>
          <dd>
            {MODE_LABEL[s.wordMode]}
            {s.private ? ' · Private' : ''}
          </dd>
        </div>
      </dl>

      <div className="lobby-actions">
        {isHost ? (
          <>
            <button className="btn btn-primary btn-lg" onClick={onStart} disabled={!canStart || busy}>
              <Icon name="play" size={16} />
              {busy ? 'Starting…' : 'Start game'}
            </button>
            {!canStart && <span className="muted small">Waiting for a second player</span>}
          </>
        ) : (
          <span className="subtle">Waiting for {host?.name ?? 'the host'} to start the game.</span>
        )}
      </div>
    </section>
  );
}
