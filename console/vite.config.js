import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'JAGENT_API_TARGET')
  const apiTarget = process.env.JAGENT_API_TARGET || env.JAGENT_API_TARGET || (mode === 'coding' ? 'http://127.0.0.1:18180' : 'http://127.0.0.1:18181')

  return {
    plugins: [vue()],
    server: {
      port: 5175,
      proxy: {
        '/api': { target: apiTarget, ...(process.env.JAGENT_CLIENT_TOKEN ? { headers: { Authorization: `Bearer ${process.env.JAGENT_CLIENT_TOKEN}` } } : {}) }
      }
    },
    preview: {
      port: 4175,
      proxy: {
        '/api': { target: apiTarget, ...(process.env.JAGENT_CLIENT_TOKEN ? { headers: { Authorization: `Bearer ${process.env.JAGENT_CLIENT_TOKEN}` } } : {}) }
      }
    }
  }
})
