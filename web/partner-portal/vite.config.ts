/// <reference types="vitest/config" />
import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';

// override with WZ_BFF_TARGET when the BFF runs elsewhere
const BFF_TARGET = process.env.WZ_BFF_TARGET ?? 'http://localhost:8090';

/**
 * Production Content-Security-Policy injected as a <meta> tag at build time only
 * (the Vite dev server needs inline scripts for React Fast Refresh).
 * The portal only talks to its own origin (BFF), so connect-src is 'self'.
 * frame-ancestors cannot be delivered via <meta>; nginx sends it as a header.
 */
function cspMeta(): Plugin {
  const policy = [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self'",
    "img-src 'self' data:",
    "font-src 'self'",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
  ].join('; ');
  return {
    name: 'wz-csp-meta',
    apply: 'build',
    transformIndexHtml(html) {
      return html.replace('<!--WZ_CSP-->', `<meta http-equiv="Content-Security-Policy" content="${policy}" />`);
    },
  };
}

const proxy = { target: BFF_TARGET, changeOrigin: false, xfwd: true };

export default defineConfig({
  plugins: [react(), cspMeta()],
  define: {
    __APP_VERSION__: JSON.stringify(process.env.npm_package_version ?? '0.0.0'),
  },
  server: {
    port: 5176,
    strictPort: true,
    proxy: {
      '/bff': proxy,
      '/oauth2': proxy,
      '/login': proxy,
      '/logout': proxy,
    },
  },
  preview: { port: 4176 },
  build: { sourcemap: false, target: 'es2022' },
  test: {
    environment: 'node',
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
