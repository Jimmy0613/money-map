import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e-kakao", workers: 1, timeout: 120000,
  reporter: [["list"]],
  use: {baseURL:process.env.MOBILE_PREVIEW_URL || "http://localhost:3000",viewport:{width:390,height:844},trace:"off",screenshot:"off",video:"off"},
});
