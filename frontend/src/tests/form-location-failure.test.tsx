import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, expect, it, vi } from "vitest";
import { TransactionForm } from "../features/transaction-form";
const mocks = vi.hoisted(() => ({ api: vi.fn(), push: vi.fn() }));
vi.mock("../shared/api", async (original) => ({
  ...(await original<typeof import("../shared/api")>()),
  api: mocks.api,
  csrf: async () => {},
}));
vi.mock("../shared/providers", () => ({
  useAuth: () => ({ user: { today: "2026-09-24" } }),
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mocks.push, back: vi.fn() }),
}));
beforeEach(() => {
  mocks.api.mockReset();
  mocks.push.mockReset();
  vi.stubEnv("NEXT_PUBLIC_KAKAO_MAP_APP_KEY", "");
  mocks.api.mockImplementation(async (_path, options) =>
    options?.method === "POST"
      ? { id: "synthetic-id" }
      : {
          items: [{ id: "c1", name: "식비", type: "EXPENSE", archived: false }],
        },
  );
});
it.each([1, 3])(
  "위치 오류 코드 %s 뒤에도 금액·날짜·메모를 보존하고 위치 없이 제출한다",
  async (code) => {
    const getCurrentPosition = vi.fn(
      (
        _ok: PositionCallback,
        fail: PositionErrorCallback,
        options?: PositionOptions,
      ) => {
        expect(options).toMatchObject({ timeout: 10000 });
        fail({ code } as GeolocationPositionError);
      },
    );
    Object.defineProperty(navigator, "geolocation", {
      configurable: true,
      value: { getCurrentPosition },
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <TransactionForm />
      </QueryClientProvider>,
    );
    await screen.findByRole("option", { name: "식비" });
    fireEvent.change(screen.getByLabelText("금액 · 원"), {
      target: { value: "12000" },
    });
    fireEvent.change(screen.getByLabelText("거래일"), {
      target: { value: "2026-09-15" },
    });
    fireEvent.change(screen.getByLabelText("메모 · 선택"), {
      target: { value: "보존할 합성 메모" },
    });
    fireEvent.change(screen.getByLabelText("카테고리", { exact: true }), {
      target: { value: "c1" },
    });
    fireEvent.click(screen.getByText("현재 위치"));
    fireEvent.click(screen.getByText("현재 위치 확인 · 재시도"));
    await screen.findByText(
      code === 1
        ? /위치 권한이 거부되었습니다/
        : /위치 확인 시간이 초과되었습니다/,
    );
    expect(getCurrentPosition.mock.calls[0][2]).toMatchObject({
      timeout: 10000,
    });
    fireEvent.click(screen.getByText("위치 없이 계속하기"));
    expect(screen.getByLabelText("금액 · 원")).toHaveValue("12000");
    expect(screen.getByLabelText("거래일")).toHaveValue("2026-09-15");
    expect(screen.getByLabelText("메모 · 선택")).toHaveValue(
      "보존할 합성 메모",
    );
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() =>
      expect(mocks.push).toHaveBeenCalledWith("/transactions/synthetic-id"),
    );
    const posted = mocks.api.mock.calls.find(
      ([, options]) => options?.method === "POST",
    )!;
    expect(JSON.parse(posted[1].body)).toMatchObject({
      amount: "12000",
      transactionDate: "2026-09-15",
      memo: "보존할 합성 메모",
      location: null,
    });
  },
);
