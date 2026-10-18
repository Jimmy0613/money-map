import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e-real",
  workers: 1,
  timeout: 90000,
  reporter: [["list"]],
  use: {
    baseURL: "http://localhost:3000",
    viewport: { width: 360, height: 800 },
    trace: "off",
    screenshot: "off",
    video: "off",
  },
});
