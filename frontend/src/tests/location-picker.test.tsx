import React from "react";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { it, expect, vi } from "vitest";
import { LocationPicker } from "../features/location-picker";
it("키가 없더라도 위치 없음 선택을 유지한다", async () => {
  vi.stubEnv("NEXT_PUBLIC_KAKAO_MAP_APP_KEY", "");
  const changed = vi.fn();
  render(<LocationPicker value={null} onChange={changed} />);
  fireEvent.click(screen.getByText("지도 선택"));
  await screen.findByText(/지도 키가 설정되지 않았습니다/);
  fireEvent.click(screen.getByText("위치 없이 계속하기"));
  expect(changed).toHaveBeenCalledWith(null);
});
it("1000m 초과 시 명시적 확인 후에만 확정한다", async () => {
  vi.stubEnv("NEXT_PUBLIC_KAKAO_MAP_APP_KEY", "");
  Object.defineProperty(navigator, "geolocation", {
    configurable: true,
    value: {
      getCurrentPosition: (ok: PositionCallback) =>
        ok({
          coords: { latitude: 37.5, longitude: 127, accuracy: 1000.001 },
          timestamp: Date.now(),
        } as GeolocationPosition),
    },
  });
  const changed = vi.fn();
  render(<LocationPicker value={null} onChange={changed} />);
  fireEvent.click(screen.getByText("현재 위치"));
  fireEvent.click(screen.getByText("현재 위치 확인 · 재시도"));
  await screen.findByText(/오차 반경이 1,000m를 초과/);
  expect(screen.queryByText("이 위치 확정")).not.toBeInTheDocument();
  fireEvent.click(screen.getByText("오차를 확인했으며 그대로 사용"));
  fireEvent.click(screen.getByText("이 위치 확정"));
  await waitFor(() =>
    expect(changed).toHaveBeenCalledWith(
      expect.objectContaining({
        lowAccuracyAccepted: true,
        accuracyM: "1000.001",
      }),
    ),
  );
});

it("렌더와 선택창 열기는 위치를 수집하지 않고 명시적 요청만 한 번 수집한다", async () => {
  const getCurrentPosition=vi.fn((_ok:PositionCallback,fail:PositionErrorCallback)=>fail({code:1} as GeolocationPositionError));
  const watchPosition=vi.fn();
  Object.defineProperty(navigator,"geolocation",{configurable:true,value:{getCurrentPosition,watchPosition}});
  render(<LocationPicker value={null} onChange={vi.fn()}/>);
  expect(getCurrentPosition).not.toHaveBeenCalled();expect(watchPosition).not.toHaveBeenCalled();
  fireEvent.click(screen.getByText("현재 위치"));expect(getCurrentPosition).not.toHaveBeenCalled();
  fireEvent.click(screen.getByText("현재 위치 확인 · 재시도"));
  await screen.findByText(/위치 없이 계속/);
  expect(getCurrentPosition).toHaveBeenCalledTimes(1);expect(watchPosition).not.toHaveBeenCalled();
});
