import React from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { AuthForm } from "../features/auth-form";
const mocks = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("../shared/providers", () => ({
  useAuth: () => ({ reload: vi.fn() }),
}));
vi.mock("../shared/api", async (original) => ({
  ...(await original<typeof import("../shared/api")>()),
  api: mocks.api,
  csrf: vi.fn(),
}));
import { ApiError } from "../shared/api";
it("서버 필드 오류를 입력란의 접근성 설명으로 연결한다", async () => {
  mocks.api.mockRejectedValue(
    new ApiError(400, "VALIDATION_FAILED", "입력 확인", [
      {
        field: "email",
        code: "INVALID_VALUE",
        message: "이메일을 다시 확인하세요.",
      },
    ]),
  );
  render(<AuthForm />);
  fireEvent.change(screen.getByLabelText("이메일", { exact: true }), {
    target: { value: "fixture@example.invalid" },
  });
  fireEvent.change(screen.getByLabelText("비밀번호", { exact: true }), {
    target: { value: "Synthetic123" },
  });
  fireEvent.click(screen.getByRole("button", { name: "로그인" }));
  await screen.findByText("이메일을 다시 확인하세요.");
  expect(
    screen.getByLabelText("이메일", { exact: true }),
  ).toHaveAccessibleDescription("이메일을 다시 확인하세요.");
});
