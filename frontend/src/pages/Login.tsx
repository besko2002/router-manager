import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError, session } from '../api/client';

export function Login() {
  const navigate = useNavigate();
  const [setupRequired, setSetupRequired] = useState<boolean | null>(null);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [visible, setVisible] = useState(false);
  const [error, setError] = useState('');
  const [retryUntil, setRetryUntil] = useState(0);
  const [now, setNow] = useState(0);
  const [busy, setBusy] = useState(false);
  const submitting = useRef(false);
  // Retain the request through StrictMode's effect replay, but ignore stale callbacks.
  const statusRequest = useRef<ReturnType<typeof api.setupStatus> | null>(null);
  const remaining = Math.max(0, Math.ceil((retryUntil - now) / 1000));
  useEffect(() => {
    let active = true;
    statusRequest.current ??= api.setupStatus();
    statusRequest.current.then(answer => { if (active) setSetupRequired(answer.setupRequired); })
      .catch(cause => { if (active) setError(cause instanceof Error ? cause.message : 'Cannot check setup status'); });
    return () => { active = false; };
  }, []);
  useEffect(() => {
    if (!retryUntil) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [retryUntil]);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (submitting.current || remaining > 0 || setupRequired === null) return;
    setError('');
    if (setupRequired && password.length < 10) { setError('Password must have at least 10 characters'); return; }
    if (setupRequired && password !== confirm) { setError('Passwords do not match'); return; }
    submitting.current = true;
    setBusy(true);
    try {
      if (setupRequired) await api.setup(username, password);
      const answer = await api.login(username, password);
      sessionStorage.removeItem('router-session-notice');
      session.set(answer.token);
      navigate('/', { replace: true });
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 409 && setupRequired) {
        setError('Owner account already created, please sign in.');
        setSetupRequired(false);
      } else setError(cause instanceof Error ? cause.message : 'Authentication failed');
      if (cause instanceof ApiError && cause.status === 429) {
        const instant = Date.now(); setNow(instant); setRetryUntil(instant + (cause.retryAfter ?? 60) * 1000);
      }
    } finally { submitting.current = false; setBusy(false); }
  }
  return <div className="login-shell"><section className="card login-card"><span className="eyebrow">HOME NETWORK</span><h1>Router Manager</h1>
    {setupRequired === null && !error && <p role="status">Checking owner account…</p>}
    {setupRequired === null && error && <p role="alert">{error}</p>}
    {setupRequired !== null && <><h2>{setupRequired ? 'Create your owner account' : 'Sign in'}</h2>
      {setupRequired ? <p>This is the account that protects your dashboard. Router credentials are NOT entered here — they stay in your .env file.</p> : <p>Sign in to your local dashboard.</p>}
      <form onSubmit={submit}><label>Username<input autoComplete="username" required value={username} onChange={event => setUsername(event.target.value)}/></label>
        <label>Password<input type={visible ? 'text' : 'password'} autoComplete={setupRequired ? 'new-password' : 'current-password'} required value={password} onChange={event => setPassword(event.target.value)}/></label>
        {setupRequired && <><button type="button" className="password-toggle" aria-label={visible ? 'Hide password' : 'Show password'} onClick={() => setVisible(!visible)}>{visible ? 'Hide password' : 'Show password'}</button>
          <label>Confirm password<input type={visible ? 'text' : 'password'} autoComplete="new-password" required value={confirm} onChange={event => setConfirm(event.target.value)}/></label></>}
        {error && <p role="alert">{error}{remaining > 0 && ` Try again in ${remaining}s.`}</p>}
        <button type="submit" disabled={busy || remaining > 0}>{busy ? (setupRequired ? 'Creating account…' : 'Signing in…') : setupRequired ? 'Create owner account' : 'Log in'}</button></form></>}
  </section></div>;
}
