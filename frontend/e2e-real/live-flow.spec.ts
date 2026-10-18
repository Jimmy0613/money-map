import { test, expect } from "@playwright/test";
import { randomUUID } from "node:crypto";

for (const loseResponse of [false, true])
  test(`실제 API/DB CRUD·인증 (commit 후 응답 유실=${loseResponse})`, async ({
    page,
    context,
  }) => {
    if (process.env.MOBILE_PREVIEW_DIAGNOSTICS === "1") {
      page.on("requestfailed", (request) => {
        const path = new URL(request.url()).pathname;
        if (path.startsWith("/api/v1/auth/"))
          console.log("Auth request failed", path, request.failure()?.errorText);
      });
      page.on("response", (response) => {
        const path = new URL(response.url()).pathname;
        if (path.startsWith("/api/v1/auth/"))
          console.log("Auth response", path, response.status());
      });
    }
    const email = `e2e-${randomUUID()}@example.invalid`;
    const password = randomUUID() + "A1";
    await page.goto("/signup");
    await page.getByLabel("이메일", { exact: true }).fill(email);
    await page.getByLabel("비밀번호", { exact: true }).fill(password);
    await page.getByLabel("비밀번호 확인", { exact: true }).fill(password);
    await page.getByRole("checkbox").check();
    await page.getByRole("button", { name: "회원가입", exact: true }).click();
    await expect(
      page.getByText("가입되었습니다. 로그인해 첫 거래를 기록하세요."),
    ).toBeVisible();
    await page.getByRole("link", { name: "로그인", exact: true }).click();
    async function login() {
      await page.getByLabel("이메일", { exact: true }).fill(email);
      await page.getByLabel("비밀번호", { exact: true }).fill(password);
      await page.getByRole("button", { name: "로그인", exact: true }).click();
      await expect(
        page.getByRole("link", { name: "＋ 거래 기록" }),
      ).toBeVisible();
    }
    await login();
    await page.getByRole("link", { name: "＋ 거래 기록" }).click();
    await page.getByLabel("금액 · 원").fill("12000");
    await page
      .getByLabel("카테고리", { exact: true })
      .selectOption({ label: "식비" });
    const postKeys: string[] = [];
    if (loseResponse)
      await page.route("**/api/v1/transactions", async (route) => {
        if (route.request().method() !== "POST") return route.continue();
        postKeys.push(route.request().headers()["idempotency-key"]);
        if (postKeys.length === 1) {
          const committed = await route.fetch();
          expect(committed.status()).toBe(201);
          await route.abort("failed");
        } else await route.continue();
      });
    await page.getByRole("button", { name: "저장", exact: true }).click();
    if (loseResponse) {
      await page.getByRole("button", { name: "같은 요청 다시 확인" }).click();
      await expect.poll(() => postKeys.length).toBe(2);
      expect(postKeys[0] === postKeys[1]).toBe(true);
    }
    await expect(
      page.getByRole("heading", { name: "거래 상세" }),
    ).toBeVisible();
    await expect(page.getByText("12,000원", { exact: true })).toBeVisible();
    await expect(page.getByText("위치 없음", { exact: true })).toBeVisible();
    for (const width of [360, 390, 768, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
    }
    const detailUrl = page.url();
    if (loseResponse)
      await page.route("**/api/v1/transactions/*", async (route) => {
        if (!["PUT", "DELETE"].includes(route.request().method()))
          return route.continue();
        const committed = await route.fetch();
        expect([200, 204]).toContain(committed.status());
        await route.abort("failed");
      });
    await page.getByRole("link", { name: "수정", exact: true }).click();
    await page.getByLabel("금액 · 원").fill("15000");
    await page.getByRole("button", { name: "저장", exact: true }).click();
    if (loseResponse) {
      await expect(
        page.getByText("현재 저장 내용이 요청과 일치합니다."),
      ).toBeVisible();
      await expect(page.getByLabel("금액 · 원")).toHaveValue("15000");
      await page.goto(detailUrl);
    }
    await expect(page.getByText("15,000원", { exact: true })).toBeVisible();
    await page.reload();
    await expect(page.getByText("15,000원", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "삭제", exact: true }).click();
    await page.getByRole("button", { name: "삭제 확인", exact: true }).click();
    await expect(
      page.getByRole("heading", { name: "현재 거래가 존재하지 않습니다" }),
    ).toBeVisible();
    const second = loseResponse ? await context.newPage() : null;
    let logoutResponseLost = false;
    if (second) {
      await second.goto("/");
      await expect(
        second.getByRole("link", { name: "＋ 거래 기록" }),
      ).toBeVisible();
      await page.route(
        "**/api/v1/auth/logout",
        async (route) => {
          const committed = await route.fetch();
          expect(committed.status()).toBe(204);
          await route.abort("failed");
          logoutResponseLost = true;
        },
        { times: 1 },
      );
    }
    await page.getByRole("button", { name: "로그아웃", exact: true }).click();
    if (second) {
      await expect(
        page.getByRole("heading", { name: "로그아웃 확인 중" }),
      ).toBeVisible();
      await expect(
        second.getByRole("heading", { name: "로그아웃 확인 중" }),
      ).toBeVisible();
      // The barrier renders before the request finishes. Wait for the actual
      // committed response loss before reloading, including over slow HTTPS.
      await expect.poll(() => logoutResponseLost, { timeout: 30000 }).toBe(true);
      await expect(
        page.getByText("서버 로그아웃을 확인하지 못했습니다. 연결 후 다시 시도하세요."),
      ).toBeVisible();
      await page.reload();
      await expect(
        page.getByRole("heading", { name: "로그아웃 확인 중" }),
      ).toBeVisible();
      await page.getByRole("button", { name: "로그아웃 다시 시도" }).click();
      await expect(
        second.getByRole("heading", { name: "다시 만나 반가워요" }),
      ).toBeVisible();
      await second.close();
    }
    await expect(
      page.getByRole("heading", { name: "다시 만나 반가워요" }),
    ).toBeVisible();
    await page.reload();
    await expect(
      page.getByRole("heading", { name: "다시 만나 반가워요" }),
    ).toBeVisible();
    await login();
    await page.getByRole("link", { name: "설정", exact: true }).click();
    await page.getByLabel("현재 비밀번호", { exact: true }).fill(password);
    await page
      .getByLabel("삭제 확인 · DELETE 입력", { exact: true })
      .fill("DELETE");
    await page
      .getByRole("button", { name: "계정과 데이터 삭제", exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: "다시 만나 반가워요" }),
    ).toBeVisible();
    await page.reload();
    await expect(
      page.getByRole("heading", { name: "다시 만나 반가워요" }),
    ).toBeVisible();
  });
