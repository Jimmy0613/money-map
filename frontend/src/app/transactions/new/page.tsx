import { TransactionForm } from "../../../features/transaction-form";
export default function Page() {
  return (
    <main id="content">
      <h1>거래 기록</h1>
      <p className="muted">금액과 날짜부터. 위치는 원할 때만 남기세요.</p>
      <TransactionForm />
    </main>
  );
}
