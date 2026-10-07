import { useEffect, useRef, type ReactNode } from 'react';
export function Banner({ children, tone = 'info' }: { children: ReactNode; tone?: 'info' | 'error' | 'warning' }) {
  return <div className={`banner banner-${tone}`} role={tone === 'error' ? 'alert' : 'status'}>{children}</div>;
}
export function Modal({ title, children, onClose }: { title: string; children: ReactNode; onClose: () => void }) {
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    panel.current?.querySelector<HTMLElement>('button:not([disabled]), input')?.focus();
    function key(event: KeyboardEvent) {
      if (event.key === 'Escape') onClose();
      if (event.key !== 'Tab' || !panel.current) return;
      const items = [...panel.current.querySelectorAll<HTMLElement>('button:not([disabled]), input, a[href]')];
      if (!items.length) return;
      if (event.shiftKey && document.activeElement === items[0]) { event.preventDefault(); items[items.length - 1].focus(); }
      else if (!event.shiftKey && document.activeElement === items[items.length - 1]) { event.preventDefault(); items[0].focus(); }
    }
    document.addEventListener('keydown', key);
    return () => { document.removeEventListener('keydown', key); opener?.focus(); };
  }, [onClose]);
  return <div className="modal-backdrop" onMouseDown={event => { if (event.target === event.currentTarget) onClose(); }}>
    <div ref={panel} className="modal" role="dialog" aria-modal="true" aria-label={title} tabIndex={-1}>
      <div className="modal-head"><h2>{title}</h2><button aria-label="Close dialog" onClick={onClose}>×</button></div>{children}
    </div>
  </div>;
}
export function Toast({ message, onClose }: { message: string | null; onClose: () => void }) {
  useEffect(() => { if (!message) return; const timer = window.setTimeout(onClose, 5000); return () => window.clearTimeout(timer); }, [message, onClose]);
  return message ? <div className="toast" role="status">{message}<button onClick={onClose} aria-label="Dismiss notification">×</button></div> : null;
}
