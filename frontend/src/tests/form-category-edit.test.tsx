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
import { TransactionForm } from "../features/transaction-form";
import type { Category, Transaction } from "../shared/types";

const mocks = vi.hoisted(() => ({ api: vi.fn(), push: vi.fn() }));
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
beforeEach(() => {
  mocks.api.mockReset();
  mocks.push.mockReset();
});

it.each([
  ["EXPENSE", false],
  ["INCOME", false],
  ["EXPENSE", true],
] as const)(
  "edit retains %s category (archived=%s) after delayed category loading",
  async (type, archived) => {
    const existing: Transaction = {
      id: "t1",
      type,
      amount: "12000",
      currency: "KRW",
      category: { id: "c1", name: "Original", archived },
      transactionDate: "2026-10-18",
      memo: null,
      location: null,
      version: 2,
      createdAt: "2026-10-18T00:00:00Z",
      updatedAt: "2026-10-18T00:00:00Z",
    };
    const items: Category[] = [
      {
        id: "c1",
        name: "Original",
        type,
        archived,
        isDefault: false,
        icon: null,
        color: null,
        sortOrder: 0,
        version: 0,
      },
      {
        id: "c2",
        name: "Alternative",
        type,
        archived: false,
        isDefault: false,
        icon: null,
        color: null,
        sortOrder: 1,
        version: 0,
      },
    ];
    let resolveCategories!: (value: { items: Category[] }) => void;
    const pending = new Promise<{ items: Category[] }>((resolve) => {
      resolveCategories = resolve;
    });
    mocks.api.mockImplementation(async (_path, options) =>
      options?.method === "PUT" ? existing : pending,
    );
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <TransactionForm existing={existing} />
      </QueryClientProvider>,
    );
    expect(
      screen.queryByRole("option", { name: /Original/ }),
    ).not.toBeInTheDocument();
    await act(async () => resolveCategories({ items }));
    await screen.findByRole("option", { name: /Original/ });
    const category = screen.getByLabelText("카테고리", { exact: true });
    expect(category).toHaveValue("c1");
    // Editing only the memo must keep the original category in the write request.
    fireEvent.change(screen.getByLabelText("메모 · 선택"), {
      target: { value: "Updated memo" },
    });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() =>
      expect(mocks.push).toHaveBeenCalledWith("/transactions/t1"),
    );
    const writes = mocks.api.mock.calls.filter(
      ([, options]) => options?.method === "PUT",
    );
    expect(writes).toHaveLength(1);
    expect(JSON.parse(writes[0][1].body)).toMatchObject({
      categoryId: "c1",
      memo: "Updated memo",
    });
    fireEvent.change(category, { target: { value: "c2" } });
    expect(category).toHaveValue("c2");
    await act(async () => {
      await client.invalidateQueries({ queryKey: ["categories", "all"] });
    });
    expect(category).toHaveValue("c2");
  },
);
