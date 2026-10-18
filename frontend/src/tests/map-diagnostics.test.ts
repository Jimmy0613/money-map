import { afterEach, beforeEach, expect, it, vi } from "vitest";
const report = vi.hoisted(() => vi.fn());
vi.mock("../features/map-diagnostics", () => ({ reportMapError: report }));
let addressStatus = "ERROR";
let searchResponds = true;
beforeEach(() => {
  vi.resetModules();
  vi.useFakeTimers();
  report.mockReset();
  vi.stubEnv("NEXT_PUBLIC_KAKAO_MAP_APP_KEY", "synthetic-key");
  addressStatus = "ERROR";
  searchResponds = true;
  vi.stubGlobal("kakao", {
    maps: {
      load: (done: () => void) => done(),
      services: {
        Status: { OK: "OK", ZERO_RESULT: "ZERO" },
        Geocoder: class {
          coord2Address(
            _x: number,
            _y: number,
            done: (a: unknown[], s: string) => void,
          ) {
            done([], addressStatus);
          }
          coord2RegionCode(
            _x: number,
            _y: number,
            done: (a: unknown[], s: string) => void,
          ) {
            done([], "ZERO");
          }
        },
        Places: class {
          keywordSearch(_q: string, done: (a: unknown[], s: string) => void) {
            if (searchResponds) done([], "ERROR");
          }
        },
      },
    },
  });
});
afterEach(() => {
  vi.useRealTimers();
  document.querySelectorAll("script").forEach((s) => s.remove());
});
async function adapter() {
  const adapterModule = await import("../features/map-adapter");
  const pending = adapterModule.kakaoAdapter();
  document.querySelector("script")!.dispatchEvent(new Event("load"));
  return { adapter: await pending, coordinate: adapterModule.coordinate };
}
it("역지오코딩 오류는 고정 코드로 집계하고 주소 없음과 구분한다", async () => {
  const { adapter: a, coordinate } = await adapter();
  const point = coordinate("MAP", 37, 127);
  expect(await a.reverse(point)).toMatchObject(point);
  expect(report.mock.calls).toEqual([["ADDRESS_FAILED"]]);
  report.mockClear();
  addressStatus = "ZERO";
  await a.reverse(point);
  expect(report).not.toHaveBeenCalled();
});
it("검색 오류와 응답 시간 초과는 검색어 없이 각각 기록한다", async () => {
  const { adapter: a } = await adapter();
  await expect(a.search("synthetic private query")).rejects.toThrow();
  expect(report.mock.calls).toEqual([["SEARCH_FAILED"]]);
  searchResponds = false;
  const pending = expect(a.search("other private query")).rejects.toThrow(
    "초과",
  );
  await vi.advanceTimersByTimeAsync(10000);
  await pending;
  expect(report.mock.calls).toEqual([["SEARCH_FAILED"], ["SEARCH_TIMEOUT"]]);
});
it("SDK 객체 없는 로드 실패 후 다시 SDK를 요청할 수 있다", async () => {
  vi.stubGlobal("kakao", undefined);
  const { loadSDK } = await import("../features/map-adapter");
  const first = expect(loadSDK()).rejects.toThrow();
  document.querySelector("script")!.dispatchEvent(new Event("load"));
  await first;
  const second = expect(loadSDK()).rejects.toThrow();
  expect(document.querySelectorAll("script")).toHaveLength(1);
  document.querySelector("script")!.dispatchEvent(new Event("error"));
  await second;
  expect(report.mock.calls).toEqual([["SDK_UNAVAILABLE"], ["SDK_LOAD"]]);
});
