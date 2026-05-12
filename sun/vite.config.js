import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const backendBuild = process.env.INTEGRATED_BUILD === 'true'

export default defineConfig({
  plugins: [react()],
  base: backendBuild ? '/frontend/' : '/',
  build: {
    outDir: backendBuild ? '../src/main/resources/static/frontend' : 'dist',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
