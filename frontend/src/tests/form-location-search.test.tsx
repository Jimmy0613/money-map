import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { expect, it, vi } from "vitest";
import { TransactionForm } from "../features/transaction-form";
import { coordinate } from "../features/map-adapter";

const mocks = vi.hoisted(() => ({
  api: vi.fn(),
  push: vi.fn(),
  search: vi.fn(),
}));
vi.mock("../shared/api", async (original) => ({
  ...(await original<typeof import("../shared/api")>()),
  api: mocks.api,
  csrf: async () => {},
}));
vi.mock("../shared/providers", () => ({
  useAuth: () => ({ user: { today: "2026-10-19" } }),
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mocks.push, back: vi.fn() }),
}));
vi.mock("../features/map-adapter", async (original) => ({
  ...(await original<typeof import("../features/map-adapter")>()),
  kakaoAdapter: async () => ({
    search: mocks.search,
    reverse: async (location: unknown) => location,
    mount: () => ({ preview: vi.fn(), dispose: vi.fn() }),
  }),
}));

it("search Enter and location confirmation do not save; explicit save includes the selected place", async () => {
  const location = {
    ...coordinate("SEARCH", 37.5665, 126.978),
    externalPlaceId: "city-hall",
    placeName: "City Hall",
    roadAddress: "Seoul",
  };
  mocks.search.mockResolvedValue([location]);
  mocks.api.mockImplementation(async (_path, options) =>
    options?.method === "POST"
      ? { id: "saved-location" }
      : {
          items: [{ id: "c1", name: "Food", type: "EXPENSE", archived: false }],
        },
  );
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <TransactionForm />
    </QueryClientProvider>,
  );
  await screen.findByRole("option", { name: "Food" });
  fireEvent.change(screen.getByLabelText("금액 · 원"), {
    target: { value: "12000" },
  });
  fireEvent.change(screen.getByLabelText("카테고리", { exact: true }), {
    target: { value: "c1" },
  });
  fireEvent.click(screen.getByRole("button", { name: "장소 검색" }));
  const search = screen.getByLabelText("장소 검색어");
  fireEvent.change(search, { target: { value: "City Hall" } });
  // jsdom has no implicit form submission: assert cancellation of its trigger.
  expect(
    fireEvent.keyDown(search, {
      key: "Enter",
      code: "Enter",
      cancelable: true,
    }),
  ).toBe(false);
  fireEvent.click(await screen.findByRole("button", { name: /City Hall/ }));
  await screen.findByLabelText("선택한 위치 미리보기 지도");
  fireEvent.click(screen.getByRole("button", { name: "이 위치 확정" }));
  expect(screen.getByText("저장할 위치 지도 확인")).toBeInTheDocument();
  expect(
    mocks.api.mock.calls.filter(([, options]) => options?.method === "POST"),
  ).toHaveLength(0);
  expect(mocks.push).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "저장" }));
  await waitFor(() =>
    expect(mocks.push).toHaveBeenCalledWith("/transactions/saved-location"),
  );
  const writes = mocks.api.mock.calls.filter(
    ([, options]) => options?.method === "POST",
  );
  expect(writes).toHaveLength(1);
  expect(JSON.parse(writes[0][1].body)).toMatchObject({
    amount: "12000",
    location,
  });
});
