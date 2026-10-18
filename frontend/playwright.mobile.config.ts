import { defineConfig } from "@playwright/test";

const baseURL = process.env.MOBILE_PREVIEW_URL;
if (!baseURL || !/^https:\/\/[a-z0-9-]+\.trycloudflare\.com$/.test(baseURL)) {
  throw new Error("Set MOBILE_PREVIEW_URL to the approved temporary HTTPS origin");
}

export default defineConfig({
  testDir: "./e2e-real",
  outputDir: "../.tooling/mobile-preview/test-results",
  workers: 1,
  timeout: 120000,
  reporter: [["list"]],
  use: {
    baseURL,
    viewport: { width: 360, height: 800 },
    trace: "off",
    screenshot: "off",
    video: "off",
  },
});
