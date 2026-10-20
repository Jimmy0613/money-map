import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { Providers } from "../shared/providers";
import { ApiError } from "../shared/api";
import Home from "../app/page";
import { AuthForm } from "../features/auth-form";

const mocks = vi.hoisted(() => ({
  api: vi.fn(),
  replace: vi.fn(),
  pathname: "/",
}));
vi.mock("next/navigation", () => ({
  usePathname: () => mocks.pathname,
  useRouter: () => ({ replace: mocks.replace }),
}));
vi.mock("../shared/api", async (original) => ({
  ...(await original<typeof import("../shared/api")>()),
  api: mocks.api,
}));
beforeEach(() => {
  vi.useFakeTimers();
  mocks.api.mockReset();
  mocks.replace.mockClear();
  mocks.pathname = "/";
});
afterEach(() => vi.useRealTimers());

it("소개는 응답 없이 표시하고 로그인으로 이동해도 준비 요청과 경과 시간을 공유한다", async () => {
  let ready!: (value: unknown) => void;
  mocks.api.mockImplementation(
    () =>
      new Promise((resolve) => {
        ready = resolve;
      }),
  );
  const view = render(
    <Providers>
      <Home />
    </Providers>,
  );
  expect(screen.getByRole("link", { name: /체험하기/ })).toHaveAttribute(
    "href",
    "/login",
  );
  expect(mocks.api).toHaveBeenCalledTimes(1);
  await act(() => vi.advanceTimersByTimeAsync(7000));
  mocks.pathname = "/login";
  view.rerender(
    <Providers>
      <AuthForm />
    </Providers>,
  );
  expect(screen.getByLabelText("이메일", { exact: true })).toBeVisible();
  fireEvent.change(screen.getByLabelText("이메일", { exact: true }), {
    target: { value: "demo@money-map.example" },
  });
  expect(screen.getByRole("button", { name: "서버 준비 중…" })).toBeDisabled();
  expect(screen.getByText("기다린 시간 00:07")).toBeVisible();
  expect(mocks.api).toHaveBeenCalledTimes(1);
  await act(async () => ready(null));
  expect(screen.getByText("서버가 준비됐어요")).toBeVisible();
  expect(screen.getByRole("button", { name: "로그인" })).toBeEnabled();
  expect(screen.getByLabelText("이메일", { exact: true })).toHaveValue(
    "demo@money-map.example",
  );
  expect(mocks.replace).not.toHaveBeenCalled();
});

it("2분 이상 기다려도 로그인 입력을 유지하고 화면 종료 시 준비 요청을 취소한다", async () => {
  mocks.pathname = "/login";
  mocks.api.mockRejectedValue(new ApiError(503, "UNAVAILABLE", "starting"));
  const view = render(
    <Providers>
      <AuthForm />
    </Providers>,
  );
  fireEvent.change(screen.getByLabelText("이메일", { exact: true }), {
    target: { value: "demo@money-map.example" },
  });
  await act(() => vi.advanceTimersByTimeAsync(120000));
  expect(screen.getByText("예상보다 준비가 늦어지고 있어요")).toBeVisible();
  expect(screen.getByLabelText("이메일", { exact: true })).toHaveValue(
    "demo@money-map.example",
  );
  const calls = mocks.api.mock.calls.length;
  const signal = mocks.api.mock.calls[0][1].signal;
  view.unmount();
  expect(signal.aborted).toBe(true);
  await act(() => vi.advanceTimersByTimeAsync(60000));
  expect(mocks.api).toHaveBeenCalledTimes(calls);
});

it("절전 중 503 뒤 401은 준비 완료로 처리하며 소개를 강제로 로그인으로 넘기지 않는다", async () => {
  mocks.api
    .mockRejectedValueOnce(new ApiError(503, "UNAVAILABLE", "starting"))
    .mockRejectedValueOnce(
      new ApiError(401, "UNAUTHENTICATED", "login required"),
    );
  render(
    <Providers>
      <Home />
    </Providers>,
  );
  await act(() => vi.advanceTimersByTimeAsync(5000));
  expect(mocks.api).toHaveBeenCalledTimes(2);
  expect(screen.getByRole("link", { name: /체험하기/ })).toBeVisible();
  expect(mocks.replace).not.toHaveBeenCalled();
  await act(() => vi.advanceTimersByTimeAsync(60000));
  expect(mocks.api).toHaveBeenCalledTimes(2);
});

it("3분 뒤 자동 준비를 멈추고 개인 화면을 차단하며 수동 재연결할 수 있다", async () => {
  mocks.pathname = "/map";
  mocks.api.mockRejectedValue(new ApiError(0, "REQUEST_TIMEOUT", "timeout"));
  render(
    <Providers>
      <p>개인 화면</p>
    </Providers>,
  );
  await act(() => vi.advanceTimersByTimeAsync(180000));
  expect(screen.queryByText("개인 화면")).not.toBeInTheDocument();
  expect(mocks.replace).not.toHaveBeenCalled();
  const calls = mocks.api.mock.calls.length;
  await act(() => vi.advanceTimersByTimeAsync(60000));
  expect(mocks.api).toHaveBeenCalledTimes(calls);
  mocks.api.mockResolvedValue({
    id: "fixture",
    email: "demo@example.invalid",
    demo: true,
  });
  fireEvent.click(screen.getByRole("button", { name: "다시 연결" }));
  await act(async () => {});
  expect(screen.getByText("개인 화면")).toBeVisible();
  expect(screen.getByText("테스트 계정으로 로그인했습니다.")).toBeVisible();
});
