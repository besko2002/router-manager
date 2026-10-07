import { useCallback, useEffect, useRef, useState } from 'react';

export function useResource<T>(loader: (signal: AbortSignal) => Promise<T>, key: string, refreshMs?: number) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [nonce, setNonce] = useState(0);
  const loaderRef = useRef(loader);
  useEffect(() => { loaderRef.current = loader; });
  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    let busy = false;
    async function load() {
      if (!active || busy || document.hidden) return;
      busy = true;
      try {
        const value = await loaderRef.current(controller.signal);
        if (active) { setData(value); setError(null); setLoading(false); }
      } catch (cause) {
        if (active && !(cause instanceof Error && cause.name === 'AbortError')) {
          setError(cause instanceof Error ? cause.message : 'Request failed');
          setLoading(false);
        }
      } finally { busy = false; }
    }
    // A new query must not display results from the previous range while it loads.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setData(null);
    setLoading(true);
    setError(null);
    // StrictMode cleanup aborts its first attempt; the second mount starts a fresh request.
    void load();
    const onVisible = () => { if (!document.hidden) void load(); };
    document.addEventListener('visibilitychange', onVisible);
    const timer = refreshMs ? window.setInterval(() => { void load(); }, refreshMs) : undefined;
    return () => {
      active = false;
      controller.abort();
      document.removeEventListener('visibilitychange', onVisible);
      if (timer !== undefined) window.clearInterval(timer);
    };
  }, [key, nonce, refreshMs]);
  const reload = useCallback(() => setNonce(n => n + 1), []);
  return { data, error, loading, reload };
}
