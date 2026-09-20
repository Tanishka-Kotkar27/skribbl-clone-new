import { useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, HttpError } from '../api/client';
import { saveSession } from '../api/session';
import Logo from '../components/Logo';
import {
  DEFAULT_SETTINGS,
  SETTINGS_BOUNDS,
  type GameSettings,
  type WordMode,
} from '../types/game';

type Tab = 'create' | 'join';

const DRAW_TIMES = [15, 30, 45, 60, 80, 100, 120, 150, 180, 240];

function range(min: number, max: number): number[] {
  return Array.from({ length: max - min + 1 }, (_, i) => min + i);
}

export default function Home() {
  const navigate = useNavigate();
  const params = useParams<{ code?: string }>();

  const [tab, setTab] = useState<Tab>(params.code ? 'join' : 'create');
  const [name, setName] = useState('');
  const [code, setCode] = useState(params.code?.toUpperCase() ?? '');
  const [settings, setSettings] = useState<GameSettings>({ ...DEFAULT_SETTINGS });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  function set<K extends keyof GameSettings>(key: K, value: GameSettings[K]) {
    setSettings((prev) => ({ ...prev, [key]: value }));
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setFieldErrors({});
    setBusy(true);
    try {
      const response =
        tab === 'create'
          ? await api.createRoom(name.trim(), settings)
          : await api.joinRoom(code.trim(), name.trim());
      saveSession(response);
      navigate(`/room/${response.roomCode}`);
    } catch (err) {
      if (err instanceof HttpError) {
        setError(err.message);
        setFieldErrors(err.fieldErrors);
      } else {
        setError('Something went wrong. Please try again.');
      }
    } finally {
      setBusy(false);
    }
  }

  const nameOk = name.trim().length > 0;
  const canSubmit = nameOk && !busy && (tab === 'create' || code.trim().length === 6);
  const nameError = fieldErrors['hostName'] ?? fieldErrors['playerName'];

  return (
    <div className="home">
      <header className="home-header">
        <Logo />
      </header>

      <div className="home-grid">
        <section className="hero">
          <h1>
            Draw fast.
            <br />
            <em>Guess faster.</em>
          </h1>
          <p className="hero-lede">
            A multiplayer drawing game. One player sketches a secret word, everyone else races to
            guess it in the chat. Quicker guesses earn more points.
          </p>
          <div className="hero-art">
            <HeroSketch />
          </div>
        </section>

        <form className="panel entry-card" onSubmit={submit} noValidate>
          <div className="tabs" role="tablist">
            <button
              type="button"
              role="tab"
              aria-selected={tab === 'create'}
              className={`tab ${tab === 'create' ? 'active' : ''}`}
              onClick={() => setTab('create')}
            >
              New room
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={tab === 'join'}
              className={`tab ${tab === 'join' ? 'active' : ''}`}
              onClick={() => setTab('join')}
            >
              Join a room
            </button>
          </div>

          <div className="form-stack">
            <label className="field">
              <span className="field-label">Your name</span>
              <input
                className="input"
                type="text"
                maxLength={20}
                autoComplete="nickname"
                placeholder="How others will see you"
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
              {nameError && <span className="field-error">{nameError}</span>}
            </label>

            {tab === 'join' ? (
              <label className="field">
                <span className="field-label">Room code</span>
                <input
                  className="input input-code"
                  type="text"
                  maxLength={6}
                  autoComplete="off"
                  spellCheck={false}
                  placeholder="ABC123"
                  value={code}
                  onChange={(e) => setCode(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, ''))}
                />
              </label>
            ) : (
              <>
                <div className="divider" />
                <div className="settings-grid">
                  <SelectField
                    label="Players"
                    value={settings.maxPlayers}
                    options={range(SETTINGS_BOUNDS.maxPlayers.min, SETTINGS_BOUNDS.maxPlayers.max)}
                    onChange={(v) => set('maxPlayers', v)}
                    error={fieldErrors['settings.maxPlayers']}
                  />
                  <SelectField
                    label="Rounds"
                    value={settings.rounds}
                    options={range(SETTINGS_BOUNDS.rounds.min, SETTINGS_BOUNDS.rounds.max)}
                    onChange={(v) => set('rounds', v)}
                    error={fieldErrors['settings.rounds']}
                  />
                  <SelectField
                    label="Draw time"
                    value={settings.drawTimeSeconds}
                    options={DRAW_TIMES}
                    format={(v) => `${v} seconds`}
                    onChange={(v) => set('drawTimeSeconds', v)}
                    error={fieldErrors['settings.drawTimeSeconds']}
                  />
                  <SelectField
                    label="Word choices"
                    value={settings.wordChoices}
                    options={range(SETTINGS_BOUNDS.wordChoices.min, SETTINGS_BOUNDS.wordChoices.max)}
                    onChange={(v) => set('wordChoices', v)}
                    error={fieldErrors['settings.wordChoices']}
                  />
                  <SelectField
                    label="Hints"
                    value={settings.hints}
                    options={range(SETTINGS_BOUNDS.hints.min, SETTINGS_BOUNDS.hints.max)}
                    format={(v) => (v === 0 ? 'Off' : String(v))}
                    onChange={(v) => set('hints', v)}
                    error={fieldErrors['settings.hints']}
                  />
                  <label className="field">
                    <span className="field-label">Word mode</span>
                    <select
                      className="select"
                      value={settings.wordMode}
                      onChange={(e) => set('wordMode', e.target.value as WordMode)}
                    >
                      <option value="NORMAL">Normal</option>
                      <option value="HIDDEN">Hidden</option>
                      <option value="COMBINATION">Combination</option>
                    </select>
                  </label>
                </div>

                <label className="switch">
                  <span>
                    <span className="field-label" style={{ display: 'block' }}>
                      Private room
                    </span>
                    <span className="muted small">Only people with the code can join</span>
                  </span>
                  <input
                    type="checkbox"
                    checked={settings.private}
                    onChange={(e) => set('private', e.target.checked)}
                  />
                  <span className="switch-track" />
                </label>
              </>
            )}

            {error && <div className="field-error">{error}</div>}

            <button className="btn btn-primary btn-lg btn-block" type="submit" disabled={!canSubmit}>
              {busy ? 'Just a moment…' : tab === 'create' ? 'Create room' : 'Join room'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

interface SelectFieldProps {
  label: string;
  value: number;
  options: number[];
  format?: (value: number) => string;
  onChange: (value: number) => void;
  error?: string;
}

function SelectField({ label, value, options, format, onChange, error }: SelectFieldProps) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      <select className="select" value={value} onChange={(e) => onChange(Number(e.target.value))}>
        {options.map((option) => (
          <option key={option} value={option}>
            {format ? format(option) : option}
          </option>
        ))}
      </select>
      {error && <span className="field-error">{error}</span>}
    </label>
  );
}

/** Small illustration of a round in progress: a sketch, the blanks and a guess. */
function HeroSketch() {
  const ink = { fill: 'none', stroke: 'var(--ink)', strokeWidth: 3.5, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const };
  return (
    <svg viewBox="0 0 460 330" role="img" aria-label="A sketch of a house with the word hidden as blanks">
      <rect x="26" y="26" width="400" height="236" rx="16" fill="#ebe5da" transform="rotate(-2.5 226 144)" />
      <rect x="20" y="16" width="400" height="236" rx="16" fill="#fff" stroke="var(--line)" transform="rotate(-1 220 134)" />

      <g transform="rotate(-1 220 134)">
        <path {...ink} d="M92 206c60 4 150-3 250 2" />
        <path {...ink} d="M140 204v-58h96v58" />
        <path {...ink} d="M126 150l62-50 64 50" />
        <path {...ink} d="M176 204v-30h22v30" />
        <rect {...ink} x="208" y="160" width="17" height="16" rx="2" />
        <path {...ink} stroke="#2f7d6d" d="M300 204v-30" />
        <circle {...ink} stroke="#2f7d6d" cx="300" cy="152" r="22" />
        <circle {...ink} stroke="var(--brand)" cx="338" cy="72" r="16" />
        <path {...ink} stroke="var(--brand)" d="M338 44v-8M338 108v-8M366 72h8M302 72h8M358 52l6-6M312 98l6-6M358 92l6 6M312 46l6 6" />
      </g>

      <g stroke="var(--ink)" strokeWidth="3.5" strokeLinecap="round">
        <path d="M150 300h22M182 300h22M214 300h22M246 300h22M278 300h22" />
      </g>

      <g transform="translate(318 238)">
        <rect width="118" height="42" rx="12" fill="var(--green-soft)" stroke="#bfe3cd" />
        <path d="M18 21l5 5 10-10" fill="none" stroke="var(--green)" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
        <text x="42" y="27" fontFamily="var(--font-display)" fontWeight="700" fontSize="16" fill="var(--green)">
          house!
        </text>
      </g>
    </svg>
  );
}
