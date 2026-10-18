import { defineConfig } from "vitest/config";
export default defineConfig({
  esbuild: { jsx: "automatic" },
  test: {
    environment: "jsdom",
    include: ["src/tests/**/*.test.{ts,tsx}"],
    setupFiles: ["src/tests/setup.ts"],
  },
});
