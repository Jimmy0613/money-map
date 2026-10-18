import React from "react";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, expect, it, vi } from "vitest";
import { validPeriod } from "../shared/ui";
import MapPage from "../app/map/page";
import { ApiError } from "../shared/api";
const mocks = vi.hoisted(() => ({
  api: vi.fn(),
  mount: vi.fn(),
  reset: undefined as undefined | (() => void),
}));
vi.mock("../shared/api", async (original) => ({
  ...(await original<typeof import("../shared/api")>()),
  api: mocks.api,
  subscribeAuth: (fn: () => void) => {
    mocks.reset = fn;
    return () => {};
  },
}));
vi.mock("../shared/providers", () => ({
  useAuth: () => ({ user: { today: "2026-09-24" } }),
}));
vi.mock("../features/map-adapter", async (original) => ({
  ...(await original<typeof import("../features/map-adapter")>()),
  kakaoAdapter: async () => ({ mount: mocks.mount }),
}));
const marker = {
  markerKey: "coord:37:127",
  title: "이전 결과",
  latitude: "37",
  longitude: "127",
  amount: "1000",
  count: 1,
};
const result = {
  items: [marker],
  viewportExpenseTotal: "1000",
  periodExpenseTotal: "1000",
  viewportCount: 1,
  locationlessCount: 0,
  requiresNarrowing: false,
  limit: 500,
  revision: "1",
};
function show() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MapPage />
    </QueryClientProvider>,
  );
}
beforeEach(() => {
  mocks.reset?.();
  mocks.api.mockReset();
  mocks.mount.mockReset();
  mocks.mount.mockReturnValue({ markers: vi.fn(), dispose: vi.fn() });
});
it("변경한 지도 조회 실패 시 이전 결과와 오류를 함께 유지한다", async () => {
  mocks.api.mockImplementation(async (path: string) => {
    if (!path.startsWith("/map/markers?")) return { items: [] };
    if (path.includes("from=2026-09-02"))
      throw new ApiError(503, "SERVICE_UNAVAILABLE", "합성 조회 실패");
    return result;
  });
  show();
  await screen.findByText(/이전 결과 ·/);
  fireEvent.change(screen.getByLabelText("시작일"), {
    target: { value: "2026-09-02" },
  });
  await screen.findByText("합성 조회 실패");
  expect(screen.getByText(/이전 결과 ·/)).toBeInTheDocument();
  expect(screen.getByText(/이전 조회 결과/)).toBeInTheDocument();
});
it("지도 화면을 다시 열면 선택한 기간과 이동한 중심을 복원한다", async () => {
  mocks.api.mockImplementation(async (path: string) =>
    path.startsWith("/map/markers?") ? result : { items: [] },
  );
  const first = show();
  await screen.findByText(/이전 결과 ·/);
  fireEvent.change(screen.getByLabelText("시작일"), {
    target: { value: "2026-09-02" },
  });
  const idle = mocks.mount.mock.calls[0][2];
  await act(async () => {
    idle({
      south: "35",
      north: "36",
      west: "128",
      east: "129",
      level: 7,
      latitude: 35.5,
      longitude: 128.5,
    });
  });
  await waitFor(() =>
    expect(
      mocks.api.mock.calls.some(([path]) => String(path).includes("south=35")),
    ).toBe(true),
  );
  first.unmount();
  show();
  await waitFor(() => expect(mocks.mount).toHaveBeenCalledTimes(2));
  expect(screen.getByLabelText("시작일")).toHaveValue("2026-09-02");
  expect(mocks.mount.mock.calls[1][1]).toMatchObject({
    latitude: 35.5,
    longitude: 128.5,
    level: 7,
  });
});
it("늦은 이전 영역 응답이 최신 영역 결과를 덮어쓰지 않는다", async () => {
  let resolveOld!: (value: unknown) => void;
  mocks.api.mockImplementation(async (path: string) => {
    if (!path.startsWith("/map/markers?")) return { items: [] };
    if (path.includes("south=35"))
      return new Promise((resolve) => {
        resolveOld = resolve;
      });
    if (path.includes("south=36"))
      return { ...result, items: [{ ...marker, title: "최신 영역" }] };
    return result;
  });
  show();
  await screen.findByText(/이전 결과 ·/);
  const idle = mocks.mount.mock.calls[0][2];
  act(() =>
    idle({
      south: "35",
      north: "36",
      west: "128",
      east: "129",
      level: 7,
      latitude: 35.5,
      longitude: 128.5,
    }),
  );
  await waitFor(() => expect(resolveOld).toBeDefined());
  act(() =>
    idle({
      south: "36",
      north: "37",
      west: "128",
      east: "129",
      level: 7,
      latitude: 36.5,
      longitude: 128.5,
    }),
  );
  await screen.findByText(/최신 영역 ·/);
  await act(async () => {
    resolveOld({ ...result, items: [{ ...marker, title: "늦은 영역" }] });
  });
  expect(screen.getByText(/최신 영역 ·/)).toBeInTheDocument();
  expect(screen.queryByText(/늦은 영역 ·/)).not.toBeInTheDocument();
});

it.each(["시작일", "종료일", "둘 다"])(
  "%s 삭제 시 지도와 상세 API를 호출하지 않는다",
  async (field) => {
    mocks.api.mockImplementation(async (path: string) =>
      path.startsWith("/map/markers?") ? result : { items: [] },
    );
    show();
    await screen.findByText(/이전 결과 ·/);
    mocks.api.mockClear();
    for (const label of field === "둘 다" ? ["시작일", "종료일"] : [field])
      fireEvent.change(screen.getByLabelText(label), { target: { value: "" } });
    await screen.findByText(/시작일과 종료일을 올바르게/);
    expect(screen.getByRole("button", { name: /이전 결과 ·/ })).toBeDisabled();
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 30));
    });
    expect(
      mocks.api.mock.calls.filter(([path]) => String(path).startsWith("/map/")),
    ).toHaveLength(0);
  },
);

it.each([
  ["2024-02-29", "2024-03-01", true],
  ["2026-02-29", "2026-03-01", false],
  ["2026-04-31", "2026-05-01", false],
  ["2026-09-02", "2026-09-01", false],
  ["invalid", "2026-09-01", false],
])("기간 %s ~ %s의 실제 날짜와 순서를 검증한다", (from, to, expected) => {
  expect(validPeriod(String(from), String(to))).toBe(expected);
});
