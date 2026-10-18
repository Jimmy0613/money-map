import { describe, it, expect, vi } from "vitest";
import { money, sameWrite, type TransactionWrite } from "../shared/types";
import { coordinate, currentLocation, loadSDK } from "../features/map-adapter";
import { monthFilter } from "../shared/ui";
describe("정밀도와 날짜", () => {
  it("JS 안전 정수 범위 이상의 합계를 보존한다", () =>
    expect(money("9999999999990000")).toBe("9,999,999,999,990,000원"));
  it("윤년과 연말의 월말을 계산한다", () => {
    expect(monthFilter("2024-02-02").to).toBe("2024-02-29");
    expect(monthFilter("2026-12-01").to).toBe("2026-12-31");
  });
  it("정규화된 현재 저장 상태와 제출 초안을 대조한다", () => {
    const a: TransactionWrite = {
      type: "EXPENSE",
      amount: "12000",
      currency: "KRW",
      categoryId: "fixture",
      transactionDate: "2026-09-15",
      memo: " coffee ",
      location: coordinate("MAP", 37.5, 127),
    };
    expect(sameWrite(a, { ...a, memo: "coffee" })).toBe(true);
    expect(sameWrite(a, { ...a, amount: "15000" })).toBe(false);
  });
});
describe("선택적 위치", () => {
  it("미리보기에서 6자리 반올림하고 경계를 검사한다", () => {
    expect(coordinate("MAP", 37.1234567, 127).latitude).toBe("37.123457");
    expect(() => coordinate("MAP", 90.00001, 0)).toThrow();
  });
  it("키 미설정 상태는 명확한 오류다", async () => {
    vi.stubEnv("NEXT_PUBLIC_KAKAO_MAP_APP_KEY", "");
    await expect(loadSDK()).rejects.toThrow("지도 키가 설정되지 않았습니다");
  });
  it("허용된 현재 위치의 오차와 시각을 전달한다", async () => {
    const getCurrentPosition = vi.fn((ok: PositionCallback) =>
      ok({
        coords: { latitude: 37.5, longitude: 127, accuracy: 1000 },
        timestamp: Date.parse("2026-09-15T01:00:00Z"),
      } as GeolocationPosition),
    );
    Object.defineProperty(navigator, "geolocation", {
      configurable: true,
      value: { getCurrentPosition },
    });
    const l = await currentLocation();
    expect(l.accuracyM).toBe("1000.000");
    expect(l.lowAccuracyAccepted).toBe(false);
    expect(getCurrentPosition).toHaveBeenCalledWith(
      expect.any(Function),
      expect.any(Function),
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 },
    );
  });
  it.each([1, 3])("위치 실패 %s를 대체 경로로 안내한다", async (code) => {
    Object.defineProperty(navigator, "geolocation", {
      configurable: true,
      value: {
        getCurrentPosition: (
          _ok: PositionCallback,
          fail: PositionErrorCallback,
        ) => fail({ code } as GeolocationPositionError),
      },
    });
    await expect(currentLocation()).rejects.toThrow("위치 없이 계속");
  });
});

it("응답 유실 대조는 JSON 키 순서와 주소 없는 지역 정규화에 독립적이다", () => {
  const input: TransactionWrite = {
    type: "EXPENSE",
    amount: "1",
    currency: "KRW",
    categoryId: "fixture",
    transactionDate: "2026-09-15",
    memo: " ",
    location: {
      ...coordinate("MAP", 37.5, 127),
      regionCode: "11680",
      regionSido: "서울특별시",
      regionSigungu: "강남구",
    },
  };
  const reordered = {
    location: { ...coordinate("MAP", 37.5, 127) },
    memo: null,
    transactionDate: input.transactionDate,
    categoryId: input.categoryId,
    currency: "KRW" as const,
    amount: "1",
    type: "EXPENSE" as const,
  };
  expect(sameWrite(input, reordered)).toBe(true);
});
