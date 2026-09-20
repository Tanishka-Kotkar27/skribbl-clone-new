import { useEffect, useRef, useState, type FormEvent } from 'react';
import Icon from './Icon';

export interface ChatLine {
  id: number;
  kind: 'chat' | 'guess' | 'correct' | 'close' | 'system' | 'error';
  name?: string;
  text: string;
  /** Seen only by the drawer and players who already guessed. */
  guessedOnly?: boolean;
  mine?: boolean;
}

interface ChatPanelProps {
  lines: ChatLine[];
  placeholder: string;
  disabled: boolean;
  onSend: (text: string) => void;
}

/** Matches GameEngine.MAX_CHAT_LENGTH on the server. */
const MAX_LENGTH = 100;

/**
 * One box for chat and guesses. The client sends everything as chat and the
 * server decides whether it was a correct guess, so the answer never has to be
 * in the browser.
 */
export default function ChatPanel({ lines, placeholder, disabled, onSend }: ChatPanelProps) {
  const [text, setText] = useState('');
  const listRef = useRef<HTMLDivElement | null>(null);

  // Keep up with new messages unless the reader has scrolled back.
  useEffect(() => {
    const list = listRef.current;
    if (!list) return;
    const nearBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 80;
    if (nearBottom) list.scrollTop = list.scrollHeight;
  }, [lines.length]);

  function submit(event: FormEvent) {
    event.preventDefault();
    const trimmed = text.trim();
    if (!trimmed || disabled) return;
    onSend(trimmed);
    setText('');
  }

  return (
    <aside className="panel chat">
      <div className="panel-header">
        <span className="panel-title">Chat</span>
      </div>

      <div className="chat-list" ref={listRef} aria-live="polite">
        {lines.length === 0 ? (
          <div className="chat-empty">Messages and guesses show up here.</div>
        ) : (
          lines.map((line) => (
            <div
              key={line.id}
              // Prefixed: a bare `chat` class would pick up the chat panel's own styles.
              className={['msg', `msg-${line.kind}`, line.guessedOnly ? 'msg-guessed-only' : '', line.mine ? 'msg-mine' : '']
                .filter(Boolean)
                .join(' ')}
            >
              {line.kind === 'correct' && <Icon name="check" size={16} />}
              {line.name && <span className="msg-name">{line.name}</span>}
              <span>{line.text}</span>
            </div>
          ))
        )}
      </div>

      <form className="chat-form" onSubmit={submit}>
        <input
          className="input"
          type="text"
          value={text}
          maxLength={MAX_LENGTH}
          placeholder={placeholder}
          disabled={disabled}
          onChange={(e) => setText(e.target.value)}
          aria-label="Message or guess"
          autoComplete="off"
        />
        <button className="btn btn-primary" type="submit" disabled={disabled || !text.trim()} aria-label="Send">
          <Icon name="send" size={17} />
        </button>
      </form>
    </aside>
  );
}
