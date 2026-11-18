import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server proxies /api to the Spring Boot backend so the browser sees a single
// origin. That removes CORS from the equation during development and mirrors the
// production setup, where nginx serves the built assets and proxies /api in turn.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const proxyTarget = env.VITE_PROXY_TARGET ?? 'http://localhost:8080';

  return {
    plugins: [react()],
    server: {
      port: 5173,
      host: true,
      proxy: {
        '/api': { target: proxyTarget, changeOrigin: true },
        '/actuator': { target: proxyTarget, changeOrigin: true },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: true,
      rollupOptions: {
        output: {
          manualChunks: {
            // React Flow is the single largest dependency; splitting it keeps the
            // application chunk small and cacheable independently of app code.
            xyflow: ['@xyflow/react'],
          },
        },
      },
    },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      css: false,
      coverage: {
        provider: 'v8',
        reporter: ['text', 'lcov'],
        include: ['src/**/*.{ts,tsx}'],
        exclude: ['src/main.tsx', 'src/vite-env.d.ts', 'src/test/**'],
      },
    },
  };
});
