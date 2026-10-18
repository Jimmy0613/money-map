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
import Statistics from "../app/statistics/page";
import { LocationPicker, LocationPreview } from "../features/location-picker";
import { coordinate } from "../features/map-adapter";
import Categories from "../app/settings/categories/page";
import MapPage from "../app/map/page";
import { ApiError } from "../shared/api";
const mocks = vi.hoisted(() => ({ api: vi.fn(), adapter: vi.fn() }));
vi.mock("../shared/api", async (original) => ({
  ...(await original<typeof import("../shared/api")>()),
  api: mocks.api,
  csrf: async () => {},
  subscribeAuth: () => () => {},
}));
vi.mock("../shared/providers", () => ({
  useAuth: () => ({ user: { today: "2026-09-23" } }),
}));
vi.mock("../features/map-adapter", async (original) => ({
  ...(await original<typeof import("../features/map-adapter")>()),
  kakaoAdapter: mocks.adapter,
}));
function show(element: React.ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  return render(
    <QueryClientProvider client={client}>{element}</QueryClientProvider>,
  );
}
beforeEach(() => {
  mocks.api.mockReset();
  mocks.adapter.mockReset();
});
it("연속 카테고리 이름 변경과 보관은 매번 최신 버전을 전송한다", async () => {
  let item = {
    id: "c1",
    type: "EXPENSE",
    name: "처음",
    isDefault: false,
    archived: false,
    icon: null,
    color: null,
    sortOrder: 0,
    version: 0,
  };
  const versions: string[] = [];
  mocks.api.mockImplementation(async (_path, options) => {
    if (options?.method === "PATCH") {
      versions.push(options.headers["If-Match"]);
      if (options.headers["If-Match"] !== `"${item.version}"`)
        throw new ApiError(412, "VERSION_CONFLICT", "충돌");
      item = {
        ...item,
        ...JSON.parse(options.body),
        version: item.version + 1,
      };
      return item;
    }
    return { items: [item] };
  });
  show(<Categories />);
  const input = await screen.findByLabelText("분류 이름");
  for (const name of ["둘째", "셋째"]) {
    fireEvent.change(input, { target: { value: name } });
    fireEvent.click(screen.getByText("이름 저장"));
    await waitFor(() => expect(screen.getByText("이름 저장")).toBeEnabled());
  }
  vi.spyOn(window, "confirm").mockReturnValue(true);
  fireEvent.click(screen.getByRole("button", { name: "보관" }));
  await waitFor(() => expect(versions).toEqual(['"0"', '"1"', '"2"']));
  await waitFor(() =>
    expect(screen.queryByLabelText("분류 이름")).not.toBeInTheDocument(),
  );
});
const marker = {
  markerKey: "coord:37:127",
  title: "합성 위치",
  latitude: "37",
  longitude: "127",
  amount: "1000",
  count: 1,
};
function mapResult(revision = "1") {
  return {
    items: [marker],
    viewportExpenseTotal: "1000",
    periodExpenseTotal: "1000",
    viewportCount: 1,
    locationlessCount: 0,
    requiresNarrowing: false,
    limit: 300,
    revision,
  };
}
it("조회가 SDK보다 먼저 끝나도 준비된 지도에 Marker를 표시한다", async () => {
  let ready!: (value: unknown) => void;
  mocks.adapter.mockReturnValue(
    new Promise((resolve) => {
      ready = resolve;
    }),
  );
  mocks.api.mockImplementation(async (path: string) =>
    path.startsWith("/map/markers") ? mapResult() : { items: [] },
  );
  const markers = vi.fn();
  show(<MapPage />);
  await screen.findByText(/합성 위치 ·/);
  await act(async () => {
    ready({ mount: () => ({ markers, dispose: vi.fn() }) });
  });
  await waitFor(() =>
    expect(markers).toHaveBeenCalledWith([marker], expect.any(Function)),
  );
});
it("상세409 뒤 최신 지도 revision을 받은 다음 상세를 다시 조회한다", async () => {
  mocks.adapter.mockResolvedValue({
    mount: () => ({ markers: vi.fn(), dispose: vi.fn() }),
  });
  let reads = 0;
  const events: string[] = [];
  mocks.api.mockImplementation(async (path: string) => {
    if (path.startsWith("/map/markers?")) {
      reads++;
      events.push(`map${reads}`);
      return mapResult(String(reads));
    }
    if (path.startsWith("/map/marker-details?")) {
      const revision = new URLSearchParams(path.split("?")[1]).get("revision");
      events.push(`detail${revision}`);
      if (revision === "1") throw new ApiError(409, "CURSOR_STALE", "변경됨");
      return {
        ...marker,
        address: "합성 주소",
        items: [],
        page: { size: 20, hasNext: false, nextCursor: null },
      };
    }
    return { items: [] };
  });
  show(<MapPage />);
  fireEvent.click(await screen.findByText(/합성 위치 ·/));
  await screen.findByText("합성 주소");
  expect(events).toEqual(["map1", "detail1", "map2", "detail2"]);
});

it("삭제된 Marker는 최신 지도 확인 후 상세를 다시 요청하지 않는다", async () => {
  mocks.adapter.mockResolvedValue({
    mount: () => ({ markers: vi.fn(), dispose: vi.fn() }),
  });
  let reads = 0;
  const details: string[] = [];
  mocks.api.mockImplementation(async (path: string) => {
    if (path.startsWith("/map/markers?"))
      return ++reads === 1 ? mapResult() : { ...mapResult("2"), items: [] };
    if (path.startsWith("/map/marker-details?")) {
      details.push(path);
      throw new ApiError(409, "CURSOR_STALE", "변경됨");
    }
    return { items: [] };
  });
  show(<MapPage />);
  fireEvent.click(await screen.findByText(/합성 위치 ·/));
  await waitFor(() =>
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
  );
  expect(details).toHaveLength(1);
});

it("선택 좌표 미리보기는 해당 좌표에 Marker를 표시하고 닫을 때 해제한다", async () => {
  const preview = vi.fn(),
    dispose = vi.fn();
  const mount = vi.fn(() => ({ preview, dispose }));
  mocks.adapter.mockResolvedValue({ mount });
  const view = render(
    <LocationPreview location={coordinate("CURRENT", 35, 128)} />,
  );
  await waitFor(() => expect(preview).toHaveBeenCalled());
  expect(mount.mock.calls[0]).toEqual([
    expect.any(HTMLElement),
    expect.objectContaining({ latitude: 35, longitude: 128 }),
    expect.any(Function),
  ]);
  expect(preview).toHaveBeenCalledWith(35, 128);
  view.unmount();
  expect(dispose).toHaveBeenCalledOnce();
});
it("미리보기 지도 실패는 좌표 확정을 막지 않는 안내로 처리한다", async () => {
  mocks.adapter.mockRejectedValue(new Error("synthetic failure"));
  render(<LocationPreview location={coordinate("SEARCH", 35, 128)} />);
  expect(await screen.findByRole("status")).toHaveTextContent(
    "아래 주소와 좌표",
  );
});
it("장소·지역 카테고리 비교 범위와 전체 복귀를 API에 전달한다", async () => {
  const paths: string[] = [];
  mocks.api.mockImplementation(async (path: string) => {
    paths.push(path);
    if (path.startsWith("/statistics/places"))
      return {
        items: [
          {
            placeId: "p1",
            name: "합성 장소",
            amount: "1000",
            count: 1,
            lastTransactionDate: "2026-09-15",
          },
        ],
        identifiedExpenseTotal: "1000",
        unidentifiedLocation: { amount: "3000", count: 2 },
        locationless: { amount: "0", count: 0 },
        page: {
          hasNext: !path.includes("cursor="),
          nextCursor: "synthetic-cursor",
        },
      };
    if (path.startsWith("/statistics/regions"))
      return {
        items: [
          {
            regionKey: "CODE:12345",
            name: "합성 지역",
            amount: "1000",
            count: 3,
            percent: 100,
          },
        ],
        page: {},
        unclassifiedCount: 0,
        locationlessCount: 0,
      };
    if (path.startsWith("/statistics/summary"))
      return { incomeTotal: "0", expenseTotal: "1000", balance: "-1000" };
    if (path.startsWith("/statistics/monthly-comparison"))
      return {
        total: {
          current: "1000",
          previous: "0",
          difference: "1000",
          growthPercent: null,
        },
        categories: [],
        regions: [],
      };
    return { items: [], expenseTotal: "1000" };
  });
  show(<Statistics />);
  fireEvent.click(
    await screen.findByRole("button", { name: "합성 장소 카테고리 비교" }),
  );
  await waitFor(() =>
    expect(
      paths.some(
        (p) =>
          p.startsWith("/statistics/categories?") && p.includes("placeId=p1"),
      ),
    ).toBe(true),
  );
  fireEvent.click(
    screen.getByRole("button", { name: "합성 지역 카테고리 비교" }),
  );
  await waitFor(() =>
    expect(
      paths.some(
        (p) =>
          p.startsWith("/statistics/categories?") &&
          p.includes("regionKey=CODE%3A12345") &&
          !p.includes("placeId"),
      ),
    ).toBe(true),
  );
  expect(screen.getByText(/100% · 3건/)).toBeInTheDocument();
  expect(screen.getByText(/최근 거래일 2026-09-15/)).toBeInTheDocument();
  expect(screen.getByText(/직접 선택 위치 3,000원 · 2건/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "다음 장소" }));
  await waitFor(() =>
    expect(paths.some((p) => p.includes("cursor=synthetic-cursor"))).toBe(true),
  );
  fireEvent.change(screen.getByLabelText("장소 정렬"), {
    target: { value: "count_desc" },
  });
  await waitFor(() =>
    expect(
      paths.filter((p) => p.startsWith("/statistics/places?")).at(-1),
    ).toContain("sort=count_desc"),
  );
  expect(
    paths.filter((p) => p.startsWith("/statistics/places?")).at(-1),
  ).not.toContain("cursor=");
  expect(
    paths.filter((p) => p.startsWith("/statistics/places?")).at(-1),
  ).toContain("from=2026-09-01");

  fireEvent.click(screen.getByRole("button", { name: "전체 장소·지역 보기" }));
  expect(screen.getByText("비교 범위: 전체 장소·지역")).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "합성 장소" })).toHaveAttribute(
    "href",
    expect.stringContaining("type=EXPENSE&placeId=p1"),
  );
});

it("카테고리 412는 초안을 보존하고 사용자가 다시 저장할 때만 최신 버전을 쓴다", async () => {
  let item = {
    id: "c1",
    type: "EXPENSE",
    name: "원본",
    archived: false,
    isDefault: false,
    icon: null,
    color: null,
    version: 0,
  };
  const versions: string[] = [];
  mocks.api.mockImplementation(async (_path, options) => {
    if (options?.method === "PATCH") {
      versions.push(options.headers["If-Match"]);
      if (versions.length === 1) {
        item = { ...item, name: "다른 탭", version: 1 };
        throw new ApiError(412, "VERSION_CONFLICT", "충돌");
      }
      item = { ...item, ...JSON.parse(options.body), version: 2 };
    }
    return options?.method === "PATCH" ? item : { items: [item] };
  });
  show(<Categories />);
  const input = await screen.findByLabelText("분류 이름");
  fireEvent.change(input, { target: { value: "내 초안" } });
  fireEvent.click(screen.getByText("이름 저장"));
  await screen.findByText(/현재 이름: 다른 탭/);
  expect(input).toHaveValue("내 초안");
  expect(versions).toEqual(['"0"']);
  fireEvent.click(screen.getByText("이름 저장"));
  await waitFor(() => expect(versions).toEqual(['"0"', '"1"']));
});
it("카테고리 보관 응답 유실 뒤 재조회로 보관 상태를 반영한다", async () => {
  let item = {
    id: "c1",
    type: "EXPENSE",
    name: "원본",
    archived: false,
    isDefault: false,
    version: 0,
  };
  let writes = 0;
  mocks.api.mockImplementation(async (_path, options) => {
    if (options?.method === "PATCH") {
      writes++;
      item = { ...item, archived: true, version: 1 };
      throw new TypeError("synthetic response loss");
    }
    return { items: [item] };
  });
  vi.spyOn(window, "confirm").mockReturnValue(true);
  show(<Categories />);
  fireEvent.click(await screen.findByRole("button", { name: "보관" }));
  await screen.findByText("현재 저장 내용이 요청과 일치합니다.");
  expect(screen.queryByLabelText("분류 이름")).not.toBeInTheDocument();
  expect(writes).toBe(1);
});

it.each(["CURRENT", "SEARCH", "MAP"] as const)(
  "확정 위치 %s의 출처와 좌표 미리보기를 재수집 없이 확인한다",
  async (source) => {
    const preview = vi.fn(),
      dispose = vi.fn(),
      changed = vi.fn();
    mocks.adapter.mockResolvedValue({ mount: () => ({ preview, dispose }) });
    const location = coordinate(source, 35, 128);
    render(<LocationPicker value={location} onChange={changed} />);
    expect(screen.getByText(/위치 출처:/)).toBeInTheDocument();
    expect(mocks.adapter).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText("저장할 위치 지도 확인"));
    await waitFor(() => expect(preview).toHaveBeenCalledWith(35, 128));
    fireEvent.click(screen.getByText("저장할 위치 지도 닫기"));
    expect(dispose).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByText("저장할 위치 제거"));
    expect(changed).toHaveBeenCalledWith(null);
  },
);
it("확정 위치 미리보기 실패 시에도 주소와 제거 동작을 유지한다", async () => {
  mocks.adapter.mockRejectedValue(new Error("synthetic failure"));
  const changed = vi.fn();
  render(
    <LocationPicker
      value={{ ...coordinate("SEARCH", 35, 128), parcelAddress: "합성 지번" }}
      onChange={changed}
    />,
  );
  fireEvent.click(screen.getByText("저장할 위치 지도 확인"));
  await screen.findByText(/미리보기 지도를 표시하지 못했습니다/);
  expect(screen.getByText(/합성 지번/)).toBeInTheDocument();
  fireEvent.click(screen.getByText("저장할 위치 제거"));
  expect(changed).toHaveBeenCalledWith(null);
});
