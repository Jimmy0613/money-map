import React from "react";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, afterEach, expect, it, vi } from "vitest";
import { LocationPicker } from "../features/location-picker";
import type { LocationWrite } from "../shared/types";
const mocks = vi.hoisted(() => ({
  search: vi.fn(),
  reverse: vi.fn(),
  current: vi.fn(),
}));
vi.mock("../features/map-adapter", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../features/map-adapter")>()),
  kakaoAdapter: async () => ({ search: mocks.search, reverse: mocks.reverse }),
  currentLocation: mocks.current,
}));
const place = (name: string): LocationWrite => ({
  source: "SEARCH",
  externalPlaceId: name,
  placeName: name,
  latitude: "37.500000",
  longitude: "127.000000",
  lowAccuracyAccepted: false,
  accuracyM: null,
  capturedAt: null,
  roadAddress: null,
  parcelAddress: null,
  regionCode: null,
  regionSido: null,
  regionSigungu: null,
});
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}
beforeEach(() => {
  vi.useFakeTimers();
  mocks.search.mockReset();
  mocks.current.mockReset();
  mocks.reverse.mockReset();
  mocks.reverse.mockImplementation(async (l) => l);
});
afterEach(() => vi.useRealTimers());
async function search(term: string) {
  fireEvent.change(screen.getByRole("textbox", { name: "장소 검색어" }), {
    target: { value: term },
  });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(300);
  });
}
it("검색은 두 글자와 300ms를 기다린 후 스냅샷을 선택한다", async () => {
  const changed = vi.fn();
  mocks.search.mockResolvedValue([place("합성 카페")]);
  render(<LocationPicker value={null} onChange={changed} />);
  fireEvent.click(screen.getByText("장소 검색"));
  await search("가");
  expect(mocks.search).not.toHaveBeenCalled();
  fireEvent.change(screen.getByRole("textbox"), { target: { value: "카페" } });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(299);
  });
  expect(mocks.search).not.toHaveBeenCalled();
  await act(async () => {
    await vi.advanceTimersByTimeAsync(1);
  });
  expect(mocks.search).toHaveBeenCalledWith("카페");
  await act(async () => {
    fireEvent.click(screen.getByText("합성 카페"));
  });
  fireEvent.click(screen.getByText("이 위치 확정"));
  expect(changed).toHaveBeenCalledWith(
    expect.objectContaining({ externalPlaceId: "합성 카페" }),
  );
});
it("역순 검색 응답은 최신 결과를 덮어쓰지 않는다", async () => {
  const old = deferred<LocationWrite[]>(),
    latest = deferred<LocationWrite[]>();
  mocks.search
    .mockReturnValueOnce(old.promise)
    .mockReturnValueOnce(latest.promise);
  render(<LocationPicker value={null} onChange={vi.fn()} />);
  fireEvent.click(screen.getByText("장소 검색"));
  await search("이전");
  await search("최신");
  await act(async () => latest.resolve([place("최신 결과")]));
  await act(async () => old.resolve([place("이전 결과")]));
  expect(screen.getByText("최신 결과")).toBeInTheDocument();
  expect(screen.queryByText("이전 결과")).not.toBeInTheDocument();
});
it("검색어를 지우면 지연 응답과 진행 표시를 폐기한다", async () => {
  const old = deferred<LocationWrite[]>();
  mocks.search.mockReturnValue(old.promise);
  render(<LocationPicker value={null} onChange={vi.fn()} />);
  fireEvent.click(screen.getByText("장소 검색"));
  await search("이전");
  await search("");
  expect(screen.queryByText("위치 확인 중…")).not.toBeInTheDocument();
  await act(async () => old.resolve([place("이전 결과")]));
  expect(screen.queryByText("이전 결과")).not.toBeInTheDocument();
});
it("닫힌 현재 위치 요청은 다음 검색 창에 미리보기를 만들지 않는다", async () => {
  const old = deferred<LocationWrite>();
  mocks.current.mockReturnValue(old.promise);
  render(<LocationPicker value={null} onChange={vi.fn()} />);
  expect(mocks.current).not.toHaveBeenCalled();
  fireEvent.click(screen.getByText("현재 위치"));
  expect(mocks.current).not.toHaveBeenCalled();
  fireEvent.click(screen.getByText("현재 위치 확인 · 재시도"));
  fireEvent.click(screen.getByText("닫기"));
  fireEvent.click(screen.getByText("장소 검색"));
  await act(async () =>
    old.resolve({ ...place("이전 위치"), source: "CURRENT" }),
  );
  expect(screen.queryByText("저장할 위치 미리보기")).not.toBeInTheDocument();
});
it("주소 역변환 실패에도 선택한 좌표를 확정할 수 있다", async () => {
  mocks.search.mockResolvedValue([place("주소 없는 카페")]);
  mocks.reverse.mockRejectedValue(new Error("offline"));
  const changed = vi.fn();
  render(<LocationPicker value={null} onChange={changed} />);
  fireEvent.click(screen.getByText("장소 검색"));
  await search("카페");
  await act(async () => fireEvent.click(screen.getByText("주소 없는 카페")));
  expect(screen.getByText(/주소를 확인하지 못했습니다/)).toBeInTheDocument();
  fireEvent.click(screen.getByText("이 위치 확정"));
  expect(changed).toHaveBeenCalledWith(
    expect.objectContaining({ latitude: "37.500000", longitude: "127.000000" }),
  );
});
it("검색 없음과 검색 실패에서 위치 없이 계속할 수 있다", async () => {
  mocks.search
    .mockResolvedValueOnce([])
    .mockRejectedValueOnce(new Error("검색 연결 실패"));
  const changed = vi.fn();
  render(<LocationPicker value={null} onChange={changed} />);
  fireEvent.click(screen.getByText("장소 검색"));
  await search("없음");
  expect(screen.getByText("검색 결과가 없습니다.")).toBeInTheDocument();
  await search("실패");
  expect(screen.getByText("검색 연결 실패")).toBeInTheDocument();
  fireEvent.click(screen.getByText("위치 없이 계속하기"));
  expect(changed).toHaveBeenCalledWith(null);
});

it("도로명 없이 지번 주소만 받은 현재 위치를 미리보기와 확정 후 표시한다", async () => {
  const location = { ...place(""), source: "CURRENT" as const, placeName: null };
  mocks.current.mockResolvedValue(location);
  mocks.reverse.mockResolvedValue({ ...location, parcelAddress: "합성동 123" });
  const changed = vi.fn();
  const { rerender } = render(<LocationPicker value={null} onChange={changed} />);
  fireEvent.click(screen.getByText("현재 위치"));
  await act(async () => { fireEvent.click(screen.getByText("현재 위치 확인 · 재시도")); });
  expect(screen.getByText(/합성동 123/)).toBeVisible();
  expect(screen.queryByText("주소 없음")).not.toBeInTheDocument();
  fireEvent.click(screen.getByText("이 위치 확정"));
  const selected = changed.mock.calls[0][0];
  expect(selected.parcelAddress).toBe("합성동 123");
  rerender(<LocationPicker value={selected} onChange={changed} />);
  expect(screen.getByText(/합성동 123/)).toBeVisible();
});

it("주소 결과가 없으면 좌표 확정을 유지하며 대안을 안내한다", async () => {
  mocks.current.mockResolvedValue({ ...place(""), source: "CURRENT", placeName: null });
  render(<LocationPicker value={null} onChange={vi.fn()} />);
  fireEvent.click(screen.getByText("현재 위치"));
  await act(async () => { fireEvent.click(screen.getByText("현재 위치 확인 · 재시도")); });
  expect(screen.getByText(/이 좌표의 주소를 확인하지 못했습니다/)).toBeVisible();
  expect(screen.getByText("이 위치 확정")).toBeEnabled();
});
