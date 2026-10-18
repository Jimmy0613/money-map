import { beforeEach, it, expect, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { beforeAll, afterAll, afterEach } from "vitest";
import {
  api,
  initializeAuthBarrier,
  setLogoutPending,
  isLogoutPending,
  logout,
} from "../shared/api";
const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  initializeAuthBarrier();
  setLogoutPending(false);
});
it("오프라인 로그아웃은 지속되고 개인 요청이 차단된다", async () => {
  vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("offline")));
  await expect(logout()).rejects.toThrow();
  expect(isLogoutPending()).toBe(true);
  expect(localStorage.getItem("mm.logoutPending")).toBe("true");
  await expect(api("/users/me")).rejects.toMatchObject({
    code: "LOGOUT_PENDING",
  });
});
it("로그아웃 이후 늦은 응답은 버린다", async () => {
  let resolve!: (r: Response) => void;
  vi.stubGlobal(
    "fetch",
    vi.fn(
      () =>
        new Promise<Response>((r) => {
          resolve = r;
        }),
    ),
  );
  const request = api("/transactions");
  setLogoutPending(true);
  resolve(
    new Response(JSON.stringify({ data: { private: true } }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }),
  );
  await expect(request).rejects.toMatchObject({ name: "AbortError" });
});
it("성공한 204 확인 뒤에만 대기를 해제한다", async () => {
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ data: { headerName: "X-XSRF-TOKEN" } })),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 })),
  );
  await logout();
  expect(isLogoutPending()).toBe(false);
  expect(localStorage.getItem("mm.logoutPending")).toBeNull();
});
it("서버 오류를 0원 성공으로 바꾸지 않는다", async () => {
  server.use(
    http.get("*/api/v1/statistics/summary", () =>
      HttpResponse.json(
        { error: { code: "SERVICE_UNAVAILABLE", message: "다시 시도하세요." } },
        { status: 503 },
      ),
    ),
  );
  vi.unstubAllGlobals();
  await expect(api("/statistics/summary")).rejects.toMatchObject({
    status: 503,
    code: "SERVICE_UNAVAILABLE",
  });
});

it("access 만료 시 한 번 갱신하고 새 CSRF로 변경 요청을 재시도한다", async () => {
  const response = (data: unknown, status = 200) =>
    new Response(JSON.stringify(data), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  document.cookie = "XSRF-TOKEN=old-csrf";
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      response({ error: { code: "ACCESS_EXPIRED", message: "만료" } }, 401),
    )
    .mockResolvedValueOnce(response({ data: { headerName: "X-XSRF-TOKEN" } }))
    .mockImplementationOnce(async () => {
      document.cookie = "XSRF-TOKEN=; Max-Age=0";
      return response({ data: { refreshed: true } });
    })
    .mockImplementationOnce(async () => {
      document.cookie = "XSRF-TOKEN=new-csrf";
      return response({ data: { headerName: "X-XSRF-TOKEN" } });
    })
    .mockResolvedValueOnce(response({ data: { saved: true } }));
  vi.stubGlobal("fetch", fetcher);
  await expect(
    api("/transactions/t1", { method: "PUT", body: "{}" }),
  ).resolves.toEqual({ saved: true });
  expect(fetcher.mock.calls.map((c) => c[0])).toEqual([
    "/api/v1/transactions/t1",
    "/api/v1/auth/csrf",
    "/api/v1/auth/refresh",
    "/api/v1/auth/csrf",
    "/api/v1/transactions/t1",
  ]);
  expect(
    (fetcher.mock.calls[4][1].headers as Headers).get("X-XSRF-TOKEN"),
  ).toBe("new-csrf");
});
