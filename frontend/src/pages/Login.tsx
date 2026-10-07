import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError, session } from '../api/client';

export function Login() {
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [retryUntil, setRetryUntil] = useState(0);
  const [now, setNow] = useState(0);
  const [busy, setBusy] = useState(false);
  const remaining = Math.max(0, Math.ceil((retryUntil - now) / 1000));
  useEffect(() => {
    if (!retryUntil) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [retryUntil]);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy || remaining > 0) return;
    setBusy(true);
    setError('');
    try {
      const answer = await api.login(username, password);
      sessionStorage.removeItem('router-session-notice');
      session.set(answer.token);
      navigate('/', { replace: true });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Login failed');
      if (cause instanceof ApiError && cause.status === 429) {
        const instant = Date.now(); setNow(instant); setRetryUntil(instant + (cause.retryAfter ?? 60) * 1000);
      }
    } finally { setBusy(false); }
  }
  return <div className="login-shell"><section className="card login-card"><span className="eyebrow">HOME NETWORK</span><h1>Router Manager</h1><p>Sign in to your local dashboard.</p>
    <form onSubmit={submit}><label>Username<input autoComplete="username" required value={username} onChange={event => setUsername(event.target.value)}/></label>
      <label>Password<input type="password" autoComplete="current-password" required value={password} onChange={event => setPassword(event.target.value)}/></label>
      {error && <p role="alert">{error}{remaining > 0 && ` Try again in ${remaining}s.`}</p>}
      <button type="submit" disabled={busy || remaining > 0}>{busy ? 'Signing in…' : 'Log in'}</button></form>
  </section></div>;
}
