import { useEffect, useState } from 'react';
import { NavLink, Route, Routes, Link, Navigate, useLocation } from 'react-router-dom';
import { api, session } from './api/client';
import { Dashboard } from './pages/Dashboard';
import { Devices } from './pages/Devices';
import { Usage } from './pages/Usage';
import { Settings } from './pages/Settings';
import { Login } from './pages/Login';

export function App() {
  const location = useLocation();
  const [token, setToken] = useState(session.token());
  const [resolved, setResolved] = useState(false);
  const [notice, setNotice] = useState('');
  useEffect(() => {
    const listener = () => { setToken(session.token()); setResolved(false); setNotice(sessionStorage.getItem('router-session-notice') ?? ''); };
    window.addEventListener('router-session', listener);
    return () => window.removeEventListener('router-session', listener);
  }, []);
  useEffect(() => {
    if (!token) return;
    const controller = new AbortController();
    // StrictMode replays effects. Only a successful /me resolves the session.
    api.me(controller.signal).then(() => { if (!controller.signal.aborted) setResolved(true); })
      .catch(() => { if (!controller.signal.aborted) setResolved(false); });
    return () => controller.abort();
  }, [token]);
  if (!token) return <>{location.pathname !== '/login' && <Navigate to="/login" replace/>}<div className="login-notice" role="status">{notice}</div><Login/></>;
  if (!resolved) return <p role="status" className="session-loading">Checking session…</p>;
  return <div className="shell"><aside className="sidebar"><Link className="brand" to="/"><span className="brand-icon">⌁</span><span>Router<span className="brand-light">Manager</span><small>HOME NETWORK</small></span></Link><nav aria-label="Main navigation"><NavLink to="/" end>◫ <span>Dashboard</span></NavLink><NavLink to="/devices">▤ <span>Devices</span></NavLink><NavLink to="/usage">◩ <span>Usage</span></NavLink><NavLink to="/settings">⚙ <span>Settings</span></NavLink></nav><div className="sidebar-foot"><span className="pulse"/> Local network dashboard<br/><small>Traffic is approximate</small><button type="button" onClick={() => { sessionStorage.removeItem('router-session-notice'); session.clear(); }}>Log out</button></div></aside><div className="main-area"><header className="topbar"><span>NETWORK / ROUTER MANAGER</span><span className="topbar-right">● &nbsp; LOCAL DASHBOARD</span></header><main id="main"><Routes><Route path="/" element={<Dashboard/>}/><Route path="/devices" element={<Devices/>}/><Route path="/devices/:mac" element={<Devices/>}/><Route path="/usage" element={<Usage/>}/><Route path="/settings" element={<Settings/>}/><Route path="/login" element={<Navigate to="/" replace/>}/><Route path="*" element={<div className="card not-found"><h1>Page not found</h1><p>This page does not exist.</p><Link to="/">Back to dashboard →</Link></div>}/></Routes></main><footer>Router Manager · Counters per SSID / LAN port only. No per-device traffic data.</footer></div></div>;
}
