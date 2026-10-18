"use client";
import { use } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../../../../shared/api";
import { Status } from "../../../../shared/ui";
import type { Transaction } from "../../../../shared/types";
import { TransactionForm } from "../../../../features/transaction-form";
export default function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const result = useQuery({
    queryKey: ["transaction", id],
    queryFn: () => api<Transaction>(`/transactions/${id}`),
  });
  return (
    <main id="content">
      <h1>거래 수정</h1>
      <Status
        loading={result.isLoading}
        error={result.error}
        retry={() => result.refetch()}
      />
      {result.data && <TransactionForm existing={result.data} />}
    </main>
  );
}
