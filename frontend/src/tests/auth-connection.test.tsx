import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { Providers } from "../shared/providers";
import { ApiError } from "../shared/api";

const mocks = vi.hoisted(() => ({ api: vi.fn(), replace: vi.fn() }));
vi.mock("next/navigation", () => ({
  usePathname: () => "/",
  useRouter: () => ({ replace: mocks.replace }),
}));
vi.mock("../shared/api", async (original) => ({
  ...(await original<typeof import("../shared/api")>()),
  api: mocks.api,
}));

it("연결 실패를 로그인 만료로 처리하지 않고 다시 연결할 수 있다", async () => {
  mocks.replace.mockClear();
  mocks.api
    .mockRejectedValueOnce(new ApiError(0, "REQUEST_TIMEOUT", "timeout"))
    .mockResolvedValueOnce({
      id: "fixture",
      email: "demo@example.invalid",
      demo: true,
    });
  render(
    <Providers>
      <p>개인 화면</p>
    </Providers>,
  );
  await screen.findByRole("button", { name: "다시 연결" });
  expect(screen.queryByText("개인 화면")).not.toBeInTheDocument();
  expect(mocks.replace).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "다시 연결" }));
  await screen.findByText("개인 화면");
  expect(
    screen.getByText("테스트 계정으로 로그인했습니다."),
  ).toBeInTheDocument();
});
