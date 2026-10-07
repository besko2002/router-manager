import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach, vi } from 'vitest';

// Node 26's experimental global localStorage can shadow jsdom's Storage.
const memory = new Map<string, string>();
const storage: Storage = {
  get length() { return memory.size; },
  clear() { memory.clear(); },
  getItem(key) { return memory.get(key) ?? null; },
  key(index) { return [...memory.keys()][index] ?? null; },
  removeItem(key) { memory.delete(key); },
  setItem(key, value) { memory.set(key, String(value)); },
};
Object.defineProperty(window, 'localStorage', { configurable: true, value: storage });
Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: storage });
beforeEach(() => memory.clear());
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); memory.clear(); });
