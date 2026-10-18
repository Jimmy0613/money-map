import { test, expect } from "@playwright/test";
test("Mock API: 로그인 → 위치 없이 거래 등록 → 상세", async ({ page }) => {
  let logged = false;
  let saved: Record<string, unknown> | null = null;
  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url()),
      path = url.pathname;
    let data: unknown = {};
    let status = 200;
    if (path.endsWith("/auth/csrf")) data = { headerName: "X-XSRF-TOKEN" };
    else if (path.endsWith("/auth/login")) {
      logged = true;
      data = { userId: "u", timezone: "Asia/Seoul", today: "2026-09-15" };
    } else if (path.endsWith("/users/me")) {
      if (!logged) {
        await route.fulfill({
          status: 401,
          json: { error: { code: "UNAUTHENTICATED", message: "로그인 필요" } },
        });
        return;
      }
      data = {
        id: "u",
        email: "fixture@example.invalid",
        timezone: "Asia/Seoul",
        today: "2026-09-15",
        version: 0,
      };
    } else if (path.endsWith("/categories"))
      data = {
        items: [
          {
            id: "c",
            name: "식비",
            type: "EXPENSE",
            isDefault: true,
            archived: false,
            version: 0,
          },
        ],
      };
    else if (path.endsWith("/statistics/summary"))
      data = {
        incomeTotal: "0",
        expenseTotal: "0",
        balance: "0",
        incomeCount: 0,
        expenseCount: 0,
      };
    else if (
      path.endsWith("/transactions") &&
      route.request().method() === "POST"
    ) {
      const body = route.request().postDataJSON();
      expect(body.location).toBeNull();
      expect(body.amount).toBe("12000");
      expect(route.request().headers()["idempotency-key"]).toBeTruthy();
      saved = {
        ...body,
        id: "t1",
        category: { id: "c", name: "식비", archived: false },
        version: 0,
        createdAt: "2026-09-15T01:00:00Z",
        updatedAt: "2026-09-15T01:00:00Z",
      };
      data = saved;
      status = 201;
    } else if (path.endsWith("/transactions/t1")) data = saved;
    else if (path.endsWith("/transactions"))
      data = {
        items: [],
        page: { nextCursor: null, hasNext: false, size: 20 },
        revision: "0",
        totals: { incomeTotal: "0", expenseTotal: "0", balance: "0", count: 0 },
      };
    else {
      await route.fulfill({
        status: 404,
        json: { error: { code: "NOT_FOUND", message: "없음" } },
      });
      return;
    }
    await route.fulfill({
      status,
      json: { data, meta: { requestId: "fixture" } },
    });
  });
  await page.goto("/login");
  await page
    .getByLabel("이메일", { exact: true })
    .fill("fixture@example.invalid");
  await page.getByLabel("비밀번호", { exact: true }).fill("FixturePass123");
  await page.getByRole("button", { name: "로그인", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "2026년 09월의 흐름" }),
  ).toBeVisible();
  await page.screenshot({path:"../evidence/mobile-home.png",fullPage:true});
  await page.getByRole("link", { name: "＋ 거래 기록" }).click();
  const openLocation = page.getByRole("button", { name: "장소 검색", exact: true });
  await openLocation.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("dialog", {name:"위치 선택"})).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog", {name:"위치 선택"})).not.toBeVisible();
  await expect(openLocation).toBeFocused();
  for (const width of [360,390,768,1440]) {
    await page.setViewportSize({width,height:800});
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  }
  await page.setViewportSize({width:360,height:800});
  await page.getByLabel("금액 · 원").fill("0");
  await page.getByRole("button", {name:"저장",exact:true}).click();
  await expect(page.getByLabel("금액 · 원")).toHaveAccessibleDescription("1~999,999,999,999원의 정수를 입력하세요.");
  expect(saved).toBeNull();
  await page.getByLabel("금액 · 원").fill("12000");
  await page.getByLabel("카테고리", { exact: true }).selectOption("c");
  await page.getByRole("button", { name: "저장", exact: true }).click();
  await expect(page.getByRole("heading", { name: "거래 상세" })).toBeVisible();
  await expect(page.getByText("12,000원", { exact: true })).toBeVisible();
  await page.screenshot({path:"../evidence/mobile-transaction.png",fullPage:true});
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});
test("로그아웃 대기는 새로고침에서도 개인 API보다 먼저 차단한다", async ({
  page,
}) => {
  await page.addInitScript(() =>
    localStorage.setItem("mm.logoutPending", "true"),
  );
  let privateRequests = 0;
  await page.route("**/api/v1/**", (route) => {
    privateRequests++;
    return route.abort();
  });
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "로그아웃 확인 중" }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "로그아웃 확인 중" }),
  ).toBeVisible();
  expect(privateRequests).toBe(0);
});
