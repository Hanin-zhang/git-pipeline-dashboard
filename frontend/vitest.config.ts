import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    // 这些用例只覆盖纯函数（格式化 / 状态映射），不需要 DOM
    environment: 'node',
    include: ['src/**/*.spec.ts'],
    reporters: ['default'],
  },
})
