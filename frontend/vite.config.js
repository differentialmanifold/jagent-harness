import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'JAGENT_API_TARGET')
  const apiTarget = env.JAGENT_API_TARGET || 'http://localhost:18080'

  return {
    plugins: [vue()],
    server: {
      port: 5173,
      proxy: {
        '/api': apiTarget
      }
    },
    preview: {
      port: 4173,
      proxy: {
        '/api': apiTarget
      }
    }
  }
})
