import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'JAGENT_')
  const apiTarget = process.env.JAGENT_SERVER_URL || env.JAGENT_SERVER_URL || 'http://127.0.0.1:18181'
  const token = process.env.JAGENT_CLIENT_TOKEN || env.JAGENT_CLIENT_TOKEN
  const proxy = { '/api': { target: apiTarget, ...(token ? { headers: { Authorization: `Bearer ${token}` } } : {}) } }
  return {
    plugins: [vue()],
    server: { port: 5175, proxy },
    preview: { port: 4175, proxy }
  }
})
