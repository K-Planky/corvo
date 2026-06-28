/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev-only proxy: the browser calls the Spring API (port 8080) from the Vite dev server
// (port 5173) without CORS. In production the SPA and API are same-origin behind the reverse
// proxy (spec §13), so no proxy or CORS config is needed there.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/health': 'http://localhost:8080',
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test-setup.ts'],
  },
});
